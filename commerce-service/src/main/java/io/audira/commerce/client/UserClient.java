package io.audira.commerce.client;

import io.audira.commerce.client.exceptions.UserClientException;
import io.audira.commerce.dto.UserDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

/**
 * Cliente REST para comunicarse con el microservicio de usuarios (User Service).
 * <p>
 * Esta clase encapsula las llamadas HTTP a la API de usuarios para recuperar información
 * de perfil de usuario ({@link UserDTO}). Utiliza {@link RestTemplate} para la
 * comunicación síncrona y realiza un manejo exhaustivo de errores de conexión y de cliente.
 * </p>
 *
 * @author Grupo GA01   
 * @see RestTemplate
 * 
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class UserClient {

    /**
     * Cliente de Spring utilizado para realizar las llamadas HTTP síncronas.
     * Se inyecta automáticamente gracias a la anotación {@link RequiredArgsConstructor} de Lombok.
     */
    private final RestTemplate restTemplate;

    /**
     * URL base del microservicio de usuarios.
     * <p>
     * El valor por defecto es {@code http://172.16.0.4:9001/api/users} si la propiedad
     * {@code services.user.url} no está definida en la configuración.
     * </p>
     */
    @Value("${services.user.url:http://172.16.0.4:9001/api/users}")
    private String userServiceUrl;

    /**
     * Recupera la información completa de un usuario por su identificador único.
     * <p>
     * Realiza una llamada GET al endpoint {@code /api/users/{userId}} del servicio de usuarios.
     * Si la respuesta es exitosa (200 OK), mapea el JSON a un objeto {@link UserDTO}.
     * </p>
     *
     * @param userId El identificador único del usuario (tipo {@link Long}) a buscar.
     * @return El objeto {@link UserDTO} que contiene los datos del usuario.
     * @throws RuntimeException Envuelve las excepciones de Spring (como {@link HttpClientErrorException}
     * y {@link ResourceAccessException}) para proporcionar un mensaje de error
     * consistente y detallado en caso de fallos de comunicación o errores HTTP.
     * @throws HttpClientErrorException Si la respuesta del servicio es un error de cliente (ej. 404 NOT FOUND, 400 BAD REQUEST).
     * @throws ResourceAccessException Si la conexión al servicio falla (ej. el servicio está inactivo o inaccesible).
     */
    public UserDTO getUserById(Long userId) {
        String url = userServiceUrl + "/" + userId;

        try {
            log.info("=== Fetching user information for userId: {} from URL: {} ===", userId, url);
            UserDTO user = restTemplate.getForObject(url, UserDTO.class);

            if (user == null) {
                log.error("Received null response from user service for userId: {}", userId);
                throw new UserClientException("User service returned null for userId: " + userId);
            }

            log.info("User information retrieved successfully: id={}, email={}, name={} {}",
                    user.getId(), user.getEmail(), user.getFirstName(), user.getLastName());
            return user;

        } catch (HttpClientErrorException e) {
            log.error("HTTP error fetching user information for userId: {}. Status: {}, Response: {}",
                    userId, e.getStatusCode(), e.getResponseBodyAsString(), e);
            throw new UserClientException("Failed to fetch user information for userId: " + userId + ". HTTP Status: " + e.getStatusCode(), e);

        } catch (ResourceAccessException e) {
            log.error("Connection error accessing user service at {} for userId: {}. " +
                    "Please verify that community-service is running on the correct port.",
                    url, userId, e);
            throw new UserClientException("Cannot connect to user service at " + url, e);

        } catch (Exception e) {
            log.error("Unexpected error fetching user information for userId: {} from URL: {}",
                    userId, url, e);
            throw new UserClientException("Unexpected error fetching user information for userId: " + userId, e);
       
        }
    }
}