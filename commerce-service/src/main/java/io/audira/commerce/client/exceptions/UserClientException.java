package io.audira.commerce.client.exceptions;

public class UserClientException extends RuntimeException {
    public UserClientException(String message) {
        super(message);
    }
    public UserClientException(String message, Throwable cause) {
        super(message, cause);
    }
}
