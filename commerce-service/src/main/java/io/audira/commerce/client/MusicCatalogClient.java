package io.audira.commerce.client;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Cliente REST para comunicarse con el servicio de catálogo de música (Music Catalog Service).
 * <p>
 * Esta clase se encarga de realizar llamadas HTTP al microservicio de catálogo para
 * obtener información sobre álbumes y canciones. Utiliza {@link RestTemplate} para la
 * comunicación síncrona.
 * </p>
 *
 * @author Grupo GA01
 * @see RestTemplate
 *
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MusicCatalogClient {

    /**
     * Cliente de Spring utilizado para realizar las llamadas HTTP síncronas.
     * Se inyecta automáticamente gracias a la anotación {@link RequiredArgsConstructor} de Lombok.
     */
    private final RestTemplate restTemplate;

    /**
     * URL base del microservicio de catálogo de música.
     * <p>
     * El valor por defecto es {@code http://172.16.0.4:9002/api} si la propiedad
     * {@code services.catalog.url} no está definida en la configuración.
     * </p>
     */
    @Value("${services.catalog.url:http://172.16.0.4:9002/api}")
    private String catalogServiceUrl;

    /**
     * Obtiene los IDs de todas las canciones asociadas a un álbum específico.
     * <p>
     * Llama al endpoint {@code /songs/album/{albumId}} del servicio de catálogo.
     * Implementa manejo de excepciones para errores de cliente (4xx) y fallos de conexión.
     * </p>
     *
     * @param albumId ID del álbum (tipo {@link Long}) del que se quieren obtener las canciones.
     * @return Una {@link List} de IDs de canciones ({@link Long}). Retorna una lista vacía si ocurre un error HTTP (4xx) o de conexión.
     * @throws HttpClientErrorException Si la respuesta del servicio es un error de cliente (4xx), es capturada y se devuelve una lista vacía.
     * @throws ResourceAccessException Si el servicio no está disponible o hay un fallo de red, es capturada y se devuelve una lista vacía.
     */
    public List<Long> getSongIdsByAlbum(Long albumId) {
        String url = catalogServiceUrl + "/songs/album/" + albumId;

        try {
            log.debug("Fetching songs for album {} from URL: {}", albumId, url);

            ResponseEntity<List<Map<String, Object>>> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    null,
                    new ParameterizedTypeReference<List<Map<String, Object>>>() {}
            );

            List<Map<String, Object>> songs = response.getBody();
            if (songs == null) {
                log.warn("Received null response for songs of album: {}", albumId);
                return new ArrayList<>();
            }

            List<Long> songIds = new ArrayList<>();
            for (Map<String, Object> song : songs) {
                Object idObj = song.get("id");
                if (idObj != null) {
                    songIds.add(((Number) idObj).longValue());
                }
            }

            log.debug("Retrieved {} songs for album {}", songIds.size(), albumId);
            return songIds;

        } catch (HttpClientErrorException e) {
            log.warn("HTTP error fetching songs for album: {}. Status: {}", albumId, e.getStatusCode());
            return new ArrayList<>();

        } catch (ResourceAccessException e) {
            log.warn("Connection error accessing catalog service at {} for album: {}", url, albumId);
            return new ArrayList<>();

        } catch (Exception e) {
            log.error("Unexpected error fetching songs for album: {} from URL: {}", albumId, url, e);
            return new ArrayList<>();
        }
    }

    /**
     * Obtiene la información detallada de una canción por su identificador.
     * <p>
     * Llama al endpoint {@code /songs/{songId}}. En caso de error, retorna un mapa vacío.
     * </p>
     *
     * @param songId ID de la canción (tipo {@link Long}) a buscar.
     * @return Un {@link Map} con los atributos de la canción (ej. id, title, artistId). Retorna un mapa vacío si hay un error de comunicación o si la canción no existe.
     */
    public Map<String, Object> getSongById(Long songId) {
        String url = catalogServiceUrl + "/songs/" + songId;

        try {
            log.debug("Fetching song {} from URL: {}", songId, url);
            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    null,
                    new ParameterizedTypeReference<Map<String, Object>>() {}
            );
            
            Map<String, Object> body = response.getBody();
            // Retorna el cuerpo o un mapa vacío si el cuerpo es null
            return body != null ? body : Collections.emptyMap(); 
        } catch (Exception e) {
            log.error("Error fetching song {} from catalog service", songId, e);
            // Retorna un mapa vacío en caso de excepción
            return Collections.emptyMap(); 
        }
    }

    /**
     * Obtiene la información detallada de un álbum por su identificador.
     * <p>
     * Llama al endpoint {@code /albums/{albumId}}. En caso de error, retorna un mapa vacío.
     * </p>
     *
     * @param albumId ID del álbum (tipo {@link Long}) a buscar.
     * @return Un {@link Map} con los atributos del álbum (ej. id, title, artistId). Retorna un mapa vacío si hay un error de comunicación o si el álbum no existe.
     */
    public Map<String, Object> getAlbumById(Long albumId) {
        String url = catalogServiceUrl + "/albums/" + albumId;

        try {
            log.debug("Fetching album {} from URL: {}", albumId, url);
            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    null,
                    new ParameterizedTypeReference<Map<String, Object>>() {}
            );
            
            Map<String, Object> body = response.getBody();
            // Retorna el cuerpo o un mapa vacío si el cuerpo es null
            return body != null ? body : Collections.emptyMap(); 
        } catch (Exception e) {
            log.error("Error fetching album {} from catalog service", albumId, e);
            // Retorna un mapa vacío en caso de excepción
            return Collections.emptyMap(); 
        }
    }

    /**
     * Obtiene la información transaccional mínima de una canción (ID del artista y precio).
     * <p>
     * Llama al endpoint de uso interno del catálogo: {@code /songs/{id}/details/commerce}.
     * </p>
     *
     * @param songId ID de la canción (tipo {@link Long}) a buscar.
     * @return Un {@link Map} con "artistId" y "price" o un mapa vacío si hay un error.
     */
    public Map<String, Object> getSongDetailsForCommerce(Long songId) {
        String url = catalogServiceUrl + "/songs/" + songId + "/details/commerce";

        try {
            log.debug("Fetching commerce details for song {} from URL: {}", songId, url);

            // Usamos exchange para asegurarnos de que el tipo de retorno sea Map<String, Object>
            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    null,
                    new ParameterizedTypeReference<Map<String, Object>>() {}
            );
            
            Map<String, Object> body = response.getBody();
            // Retorna el cuerpo o un mapa vacío si el cuerpo es null
            return body != null ? body : Collections.emptyMap(); 

        } catch (HttpClientErrorException.NotFound e) {
            log.warn("Song not found for commerce details: {}", songId);
            // Retorna un mapa vacío cuando la canción no se encuentra (404)
            return Collections.emptyMap(); 
        } catch (Exception e) {
            log.error("Error fetching commerce details for song {}: {}", songId, e.getMessage());
            // Retorna un mapa vacío en caso de cualquier otra excepción
            return Collections.emptyMap(); 
        }
    }
}