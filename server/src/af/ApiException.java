package af;

/** Expected API failure with an HTTP status and a user-safe message. */
final class ApiException extends RuntimeException {
    final int status;

    ApiException(int status, String message) {
        super(message);
        this.status = status;
    }
}
