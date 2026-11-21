package af;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

final class Util {
    private Util() {}

    static final Gson GSON = new GsonBuilder().serializeNulls().disableHtmlEscaping().create();
    static final ZoneId ZONE = ZoneId.of("Asia/Hong_Kong");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    static JsonObject parseObject(String body) {
        JsonElement element = JsonParser.parseString(body);
        if (!element.isJsonObject()) {
            throw new ApiException(400, "Expected a JSON object");
        }
        return element.getAsJsonObject();
    }

    static Map<String, String> parseForm(String body) {
        Map<String, String> out = new LinkedHashMap<>();
        if (body == null || body.isBlank()) return out;
        for (String part : body.split("&")) {
            int eq = part.indexOf('=');
            String key = eq < 0 ? part : part.substring(0, eq);
            String value = eq < 0 ? "" : part.substring(eq + 1);
            out.put(URLDecoder.decode(key, StandardCharsets.UTF_8), URLDecoder.decode(value, StandardCharsets.UTF_8));
        }
        return out;
    }

    static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? "" : e.getAsString();
    }

    static String required(JsonObject o, String key, int max) {
        String value = str(o, key).trim();
        if (value.isEmpty()) throw new ApiException(400, "Missing field: " + key);
        if (value.length() > max) throw new ApiException(400, "Field too long: " + key);
        return value;
    }

    static String optional(JsonObject o, String key, int max) {
        String value = str(o, key).trim();
        if (value.length() > max) throw new ApiException(400, "Field too long: " + key);
        return value;
    }

    static int integer(JsonObject o, String key, int def, int min, int max) {
        JsonElement e = o.get(key);
        int value = def;
        if (e != null && !e.isJsonNull()) {
            try {
                value = e.getAsInt();
            } catch (Exception ex) {
                throw new ApiException(400, "Invalid number: " + key);
            }
        }
        if (value < min || value > max) throw new ApiException(400, "Out of range: " + key);
        return value;
    }

    static double decimal(JsonObject o, String key, double def, double min, double max) {
        JsonElement e = o.get(key);
        double value = def;
        if (e != null && !e.isJsonNull()) {
            try {
                value = e.getAsDouble();
            } catch (Exception ex) {
                throw new ApiException(400, "Invalid number: " + key);
            }
        }
        if (!Double.isFinite(value) || value < min || value > max) throw new ApiException(400, "Out of range: " + key);
        return value;
    }

    static boolean bool(JsonObject o, String key, boolean def) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? def : e.getAsBoolean();
    }

    static String initials(String name) {
        String cleaned = name == null ? "" : name.trim();
        if (cleaned.isEmpty()) return "?";
        String[] parts = cleaned.split("\\s+");
        if (parts.length >= 2) {
            return (firstCodePoint(parts[0]) + firstCodePoint(parts[1])).toUpperCase();
        }
        if (cleaned.codePointCount(0, cleaned.length()) >= 2) {
            int a = cleaned.codePointAt(0);
            int b = cleaned.codePointAt(Character.charCount(a));
            return new String(Character.toChars(a)) + new String(Character.toChars(b));
        }
        return cleaned.toUpperCase();
    }

    private static String firstCodePoint(String value) {
        int cp = value.codePointAt(0);
        return new String(Character.toChars(cp));
    }

    static String randomToken(int bytes) {
        byte[] data = new byte[bytes];
        RANDOM.nextBytes(data);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }

    static String inviteCode() {
        String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            sb.append(alphabet.charAt(RANDOM.nextInt(alphabet.length())));
        }
        return sb.toString();
    }

    static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] out = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : out) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    static String hashPassword(String password) {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        byte[] hash = pbkdf2(password.toCharArray(), salt, 120_000);
        return "pbkdf2$120000$" + Base64.getEncoder().encodeToString(salt) + "$"
                + Base64.getEncoder().encodeToString(hash);
    }

    static boolean verifyPassword(String password, String stored) {
        try {
            String[] parts = stored.split("\\$");
            if (parts.length != 4) return false;
            int iterations = Integer.parseInt(parts[1]);
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] expected = Base64.getDecoder().decode(parts[3]);
            byte[] actual = pbkdf2(password.toCharArray(), salt, iterations);
            return MessageDigest.isEqual(expected, actual);
        } catch (Exception ex) {
            return false;
        }
    }

    private static byte[] pbkdf2(char[] password, byte[] salt, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, 256);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    static boolean validEmail(String email) {
        return email != null && email.length() <= 200 && EMAIL.matcher(email).matches();
    }

    static Instant parseInstantInput(String raw, Instant fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        String value = raw.trim();
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ignored) {
        }
        try {
            return java.time.OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException ignored) {
        }
        try {
            return LocalDateTime.parse(value).atZone(ZONE).toInstant();
        } catch (DateTimeParseException ignored) {
        }
        try {
            return LocalDate.parse(value).atTime(18, 0).atZone(ZONE).toInstant();
        } catch (DateTimeParseException ignored) {
        }
        throw new ApiException(400, "Invalid date/time: " + raw);
    }

    static String isoOrNull(Instant value) {
        return value == null ? null : value.toString();
    }

    static String escapeHtml(String raw) {
        if (raw == null) return "";
        return raw.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    static String escapeJsonForHtml(String raw) {
        return raw.replace("<", "\\u003c").replace(">", "\\u003e").replace("&", "\\u0026");
    }

    static double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    static String safeFilename(String name) {
        return name.replaceAll("[^A-Za-z0-9._-]+", "-").replaceAll("^-+|-+$", "");
    }
}
