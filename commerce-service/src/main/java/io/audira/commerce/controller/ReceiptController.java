package io.audira.commerce.controller;

import io.audira.commerce.dto.ReceiptDTO;
import io.audira.commerce.service.ReceiptService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * Controlador REST para manejar la generación y la consulta de Recibos de Pago (Receipts).
 * <p>
 * Los endpoints base se mapean a {@code /api/receipts}. Esta clase permite a los usuarios
 * y a otros servicios obtener el comprobante de pago asociado a una transacción específica.
 * </p>
 *
 * @author Grupo GA01
 * @see ReceiptService
 * @see ReceiptDTO
 * */
@RestController
@RequestMapping("/api/receipts")
@RequiredArgsConstructor
@Slf4j
public class ReceiptController {

    /**
     * Servicio que contiene la lógica de negocio para la gestión de recibos.
     */
    private final ReceiptService receiptService;

    /**
     * Obtiene un recibo utilizando el ID de pago (paymentId) asociado.
     * <p>
     * Mapeo: {@code GET /api/receipts/payment/{paymentId}}
     * </p>
     *
     * @param paymentId El ID primario del registro de pago (tipo {@link Long}).
     * @return {@link ResponseEntity} que contiene el objeto {@link ReceiptDTO} (200 OK) si el recibo se encuentra, o un mensaje de error (404 NOT FOUND) si no existe.
     */
    @GetMapping("/payment/{paymentId}")
    public ResponseEntity<Object> getReceiptByPaymentId(@PathVariable Long paymentId) {
        try {
            log.info("GET /api/receipts/payment/{} - Fetching receipt", paymentId);
            ReceiptDTO receipt = receiptService.getReceiptByPaymentId(paymentId);
            return ResponseEntity.ok(receipt);
        } catch (RuntimeException e) {
            log.error("Error fetching receipt for payment {}: {}", paymentId, e.getMessage());
            Map<String, String> error = new HashMap<>();
            error.put("error", e.getMessage());
            error.put("paymentId", paymentId.toString());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
        }
    }

    /**
     * Obtiene un recibo utilizando el ID de transacción de la pasarela de pago (transactionId).
     * <p>
     * Mapeo: {@code GET /api/receipts/transaction/{transactionId}}
     * </p>
     *
     * @param transactionId El identificador único de la transacción (tipo {@link String}) proporcionado por la pasarela de pago.
     * @return {@link ResponseEntity} que contiene el objeto {@link ReceiptDTO} (200 OK) si el recibo se encuentra, o un mensaje de error (404 NOT FOUND) si no existe.
     */
    @GetMapping("/transaction/{transactionId}")
    public ResponseEntity<Object> getReceiptByTransactionId(@PathVariable String transactionId) {
        try {
            log.info("GET /api/receipts/transaction/{} - Fetching receipt", transactionId);
            ReceiptDTO receipt = receiptService.getReceiptByTransactionId(transactionId);
            return ResponseEntity.ok(receipt);
        } catch (RuntimeException e) {
            log.error("Error fetching receipt for transaction {}: {}", transactionId, e.getMessage());
            Map<String, String> error = new HashMap<>();
            error.put("error", e.getMessage());
            error.put("transactionId", transactionId);
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
        }
    }

    /**
     * Genera un nuevo recibo para un pago que ya ha sido procesado.
     * <p>
     * Mapeo: {@code POST /api/receipts/generate/{paymentId}}
     * Este endpoint se utiliza si el recibo necesita ser recreado o generado por primera vez
     * después de que el pago haya sido marcado como exitoso.
     * </p>
     *
     * @param paymentId El ID primario del pago (tipo {@link Long}) para el cual se desea generar el recibo.
     * @return {@link ResponseEntity} que contiene el objeto {@link ReceiptDTO} generado (200 OK) o un mensaje de error (400 BAD REQUEST) si el pago no es apto para generar un recibo.
     */
    @PostMapping("/generate/{paymentId}")
    public ResponseEntity<Object> generateReceipt(@PathVariable Long paymentId) {
        try {
            log.info("POST /api/receipts/generate/{} - Generating receipt", paymentId);
            ReceiptDTO receipt = receiptService.generateReceipt(paymentId);
            return ResponseEntity.ok(receipt);
        } catch (RuntimeException e) {
            log.error("Error generating receipt for payment {}: {}", paymentId, e.getMessage());
            Map<String, String> error = new HashMap<>();
            error.put("error", e.getMessage());
            error.put("paymentId", paymentId.toString());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
        }
    }
}