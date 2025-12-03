package io.audira.commerce.service;

import io.audira.commerce.dto.*;
import io.audira.commerce.model.*;
import io.audira.commerce.repository.OrderRepository;
import io.audira.commerce.repository.PaymentRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Servicio de lógica de negocio responsable del procesamiento de Pagos y de la orquestación
 * del flujo transaccional posterior (actualización de orden, biblioteca y notificaciones).
 * <p>
 * Este servicio simula la interacción con una pasarela de pago externa para determinar el estado
 * y aplica las reglas de negocio necesarias para el comercio digital (ej. entrega inmediata de productos).
 * </p>
 *
 * @author Grupo GA01
 * @see PaymentRepository
 * @see OrderRepository
 * 
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final OrderRepository orderRepository;
    private final LibraryService libraryService;
    private final CartService cartService;
    private final NotificationService notificationService;
    private final Random random = new Random();

    /**
     * Contexto de persistencia utilizado para forzar la sincronización (flush) de los cambios
     * de la base de datos dentro de la transacción, permitiendo que el estado actualizado
     * sea visible inmediatamente para otros hilos o procesos (ej. notificaciones).
     */
    @PersistenceContext
    private EntityManager entityManager;

    /**
     * Procesa una solicitud de pago para una orden, simulando la interacción con una pasarela de pago.
     * <p>
     * Es el método central que gestiona la creación del registro de pago, la actualización de
     * estado de la orden a {@link OrderStatus#DELIVERED}, la adición de artículos a la biblioteca
     * del usuario y la limpieza del carrito.
     * </p>
     *
     * @param request La solicitud {@link ProcessPaymentRequest} con los detalles de la transacción.
     * @return El objeto {@link PaymentResponse} con el resultado final del pago.
     */
    @Transactional
    public PaymentResponse processPayment(ProcessPaymentRequest request) {
        log.info("Processing payment for order: {}, method: {}",
                request.getOrderId(), request.getPaymentMethod());

        // Simulate payment processing
        try {
            // Generate transaction ID
            String transactionId = "TXN-" + UUID.randomUUID().toString();

            // Create payment record (initial state: PROCESSING)
            Payment payment = Payment.builder()
                    .transactionId(transactionId)
                    .orderId(request.getOrderId())
                    .userId(request.getUserId())
                    .paymentMethod(request.getPaymentMethod())
                    .amount(request.getAmount())
                    .status(PaymentStatus.PROCESSING)
                    .retryCount(0)
                    .build();

            payment = paymentRepository.save(payment);

            // Simulate payment gateway processing
            boolean paymentSuccessful = simulatePaymentGateway(request);

            if (paymentSuccessful) {
                // --- Flujo de Éxito ---
                payment.setStatus(PaymentStatus.COMPLETED);
                payment.setCompletedAt(LocalDateTime.now());
                payment = paymentRepository.save(payment);

                // CRITICAL: Flush to database immediately so polling can see the COMPLETED status
                entityManager.flush();
                log.info("Payment status after save and flush: {}", payment.getStatus());

                // Update order status to DELIVERED since digital products are immediately available
                updateOrderStatus(request.getOrderId(), OrderStatus.DELIVERED);

                // Flush order status update to database immediately
                entityManager.flush();
                log.info("Order status updated to DELIVERED for order: {}", request.getOrderId());

                // Add purchased items to user's library
                Order order = orderRepository.findById(request.getOrderId())
                        .orElseThrow(() -> new RuntimeException("Order not found"));
                libraryService.addOrderToLibrary(order, payment.getId());

                // Clear user's cart after successful payment
                try {
                    cartService.clearCart(request.getUserId());
                    log.info("Cart cleared for user: {}", request.getUserId());
                } catch (Exception e) {
                    log.error("Failed to clear cart for user: {}", request.getUserId(), e);
                    // Don't fail the payment if cart clearing fails
                }

                // Flush again to ensure all changes are written to DB
                entityManager.flush();

                // Refresh payment from database to ensure we have the latest state
                payment = paymentRepository.findById(payment.getId())
                        .orElseThrow(() -> new RuntimeException("Payment not found after save"));

                // Send notifications for successful purchase
                try {
                    notificationService.notifySuccessfulPurchase(order, payment);
                    log.info("Purchase notifications sent for order: {}", order.getOrderNumber());
                } catch (Exception e) {
                    log.error("Failed to send purchase notifications for order: {}", order.getOrderNumber(), e);
                    // Don't fail the payment if notification fails
                }

                log.info("Payment completed successfully: {}, final status: {}", transactionId, payment.getStatus());

                PaymentDTO paymentDTO = mapToDTO(payment);
                log.info("PaymentDTO status: {}", paymentDTO.getStatus());

                return PaymentResponse.builder()
                        .success(true)
                        .transactionId(transactionId)
                        .status(PaymentStatus.COMPLETED)
                        .message("Payment processed successfully")
                        .payment(paymentDTO)
                        .build();
            } else {
                // --- Flujo de Fallo ---
                payment.setStatus(PaymentStatus.FAILED);
                payment.setErrorMessage("Payment declined by gateway");
                payment = paymentRepository.save(payment);

                // Notificar el fallo al usuario
                Order order = orderRepository.findById(request.getOrderId())
                        .orElseThrow(() -> new RuntimeException("Order not found"));
                notificationService.notifyFailedPayment(order, payment.getErrorMessage());

                log.warn("Payment failed for order: {}", request.getOrderId());

                return PaymentResponse.builder()
                        .success(false)
                        .transactionId(transactionId)
                        .status(PaymentStatus.FAILED)
                        .message("Payment was declined. Please try again or use a different payment method.")
                        .payment(mapToDTO(payment))
                        .build();
            }

        } catch (Exception e) {
            log.error("Error processing payment for order: {}", request.getOrderId(), e);
            return PaymentResponse.builder()
                    .success(false)
                    .status(PaymentStatus.FAILED)
                    .message("An error occurred while processing your payment: " + e.getMessage())
                    .build();
        }
    }

    /**
     * Intenta reintentar un pago que previamente falló.
     * <p>
     * Solo permite el reintento si el estado actual es {@link PaymentStatus#FAILED}.
     * Incrementa el contador de reintentos y llama a {@link #processPayment(ProcessPaymentRequest)}.
     * </p>
     *
     * @param paymentId El ID del registro de pago fallido a reintentar.
     * @return El {@link PaymentResponse} del nuevo intento de pago.
     * @throws RuntimeException si el pago no se encuentra o no está en estado FAILED.
     */
    @Transactional
    public PaymentResponse retryPayment(Long paymentId) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new RuntimeException("Payment not found"));

        if (payment.getStatus() != PaymentStatus.FAILED) {
            throw new RuntimeException("Only failed payments can be retried");
        }

        payment.setRetryCount(payment.getRetryCount() + 1);
        payment.setStatus(PaymentStatus.PROCESSING);
        payment.setErrorMessage(null);
        payment = paymentRepository.save(payment);

        // Create a new payment request
        ProcessPaymentRequest request = ProcessPaymentRequest.builder()
                .orderId(payment.getOrderId())
                .userId(payment.getUserId())
                .paymentMethod(payment.getPaymentMethod())
                .amount(payment.getAmount())
                .build();

        return processPayment(request);
    }

    /**
     * Obtiene una lista de todos los pagos asociados a un usuario específico.
     *
     * @param userId El ID del usuario.
     * @return Una {@link List} de {@link PaymentDTO}.
     */
    public List<PaymentDTO> getPaymentsByUserId(Long userId) {
        return paymentRepository.findByUserId(userId).stream()
                .map(this::mapToDTO)
                .collect(Collectors.toList());
    }

    /**
     * Obtiene una lista de todos los pagos asociados a una orden específica.
     *
     * @param orderId El ID de la orden.
     * @return Una {@link List} de {@link PaymentDTO}.
     */
    public List<PaymentDTO> getPaymentsByOrderId(Long orderId) {
        return paymentRepository.findByOrderId(orderId).stream()
                .map(this::mapToDTO)
                .collect(Collectors.toList());
    }

    /**
     * Obtiene un registro de pago por su ID de transacción único de la pasarela.
     *
     * @param transactionId El ID de transacción.
     * @return El objeto {@link PaymentDTO}.
     * @throws RuntimeException si el pago no se encuentra.
     */
    public PaymentDTO getPaymentByTransactionId(String transactionId) {
        Payment payment = paymentRepository.findByTransactionId(transactionId)
                .orElseThrow(() -> new RuntimeException("Payment not found"));
        return mapToDTO(payment);
    }

    /**
     * Obtiene un registro de pago por su ID primario.
     *
     * @param paymentId El ID primario del pago.
     * @return El objeto {@link PaymentDTO}.
     * @throws RuntimeException si el pago no se encuentra.
     */
    public PaymentDTO getPaymentById(Long paymentId) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new RuntimeException("Payment not found"));
        return mapToDTO(payment);
    }

    /**
     * Procesa el reembolso de un pago previamente completado.
     * <p>
     * Solo permite el reembolso si el estado actual es {@link PaymentStatus#COMPLETED}.
     * Actualiza el estado del pago a {@link PaymentStatus#REFUNDED} y la orden asociada a {@link OrderStatus#CANCELLED}.
     * </p>
     *
     * @param paymentId El ID del pago a reembolsar.
     * @return El {@link PaymentResponse} con el resultado del reembolso.
     * @throws RuntimeException si el pago no se encuentra o no está en estado COMPLETED.
     */
    @Transactional
    public PaymentResponse refundPayment(Long paymentId) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new RuntimeException("Payment not found"));

        if (payment.getStatus() != PaymentStatus.COMPLETED) {
            throw new RuntimeException("Only completed payments can be refunded");
        }

        payment.setStatus(PaymentStatus.REFUNDED);
        payment = paymentRepository.save(payment);

        // Update order status
        updateOrderStatus(payment.getOrderId(), OrderStatus.CANCELLED);

        // Notificar al usuario del reembolso
        notificationService.notifyRefund(payment);

        return PaymentResponse.builder()
                .success(true)
                .transactionId(payment.getTransactionId())
                .status(PaymentStatus.REFUNDED)
                .message("Payment refunded successfully")
                .payment(mapToDTO(payment))
                .build();
    }

    /**
     * Simula la lógica de una pasarela de pago externa.
     * <p>
     * Simula un retraso de red (1-3 segundos) y aplica una tasa de fallo aleatorio.
     * También incluye una tarjeta de prueba que siempre falla ({@code cardNumber} que empieza por "4000").
     * </p>
     *
     * @param request La solicitud de pago.
     * @return {@code true} si la simulación es exitosa, {@code false} si falla.
     */
    private boolean simulatePaymentGateway(ProcessPaymentRequest request) {
        // Simulate network delay
        try {
            Thread.sleep(1000L + random.nextInt(2000)); // 1-3 seconds
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Validate payment details
        if (request.getPaymentDetails() != null) {
            String cardNumber = request.getPaymentDetails().get("cardNumber");
            if (cardNumber != null && cardNumber.startsWith("4000")) {
                // Test card that always fails
                return false;
            }
        }

        // 90% success rate
        return random.nextInt(100) < 90;
    }

    /**
     * Actualiza el estado de una orden de compra específica.
     *
     * @param orderId El ID de la orden.
     * @param status El nuevo estado ({@link OrderStatus}).
     */
    private void updateOrderStatus(Long orderId, OrderStatus status) {
        orderRepository.findById(orderId).ifPresent(order -> {
            OrderStatus oldStatus = order.getStatus();
            order.setStatus(status);
            orderRepository.save(order);
            // Notificar el cambio de estado
            notificationService.notifyOrderStatusChange(order, oldStatus, status);
        });
    }

    /**
     * Mapea una entidad {@link Payment} a su respectivo Data Transfer Object (DTO) {@link PaymentDTO}.
     * <p>
     * Método auxiliar privado.
     * </p>
     *
     * @param payment La entidad {@link Payment} de origen.
     * @return El {@link PaymentDTO} resultante.
     */
    private PaymentDTO mapToDTO(Payment payment) {
        return PaymentDTO.builder()
                .id(payment.getId())
                .transactionId(payment.getTransactionId())
                .orderId(payment.getOrderId())
                .userId(payment.getUserId())
                .paymentMethod(payment.getPaymentMethod())
                .status(payment.getStatus())
                .amount(payment.getAmount())
                .errorMessage(payment.getErrorMessage())
                .retryCount(payment.getRetryCount())
                .metadata(payment.getMetadata())
                .createdAt(payment.getCreatedAt())
                .updatedAt(payment.getUpdatedAt())
                .completedAt(payment.getCompletedAt())
                .build();
    }
}