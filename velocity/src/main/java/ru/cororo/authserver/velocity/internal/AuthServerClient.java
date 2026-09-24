package ru.cororo.authserver.velocity.internal;

import com.google.gson.Gson;
import ru.cororo.authserver.bridge.AccountApi;
import ru.cororo.authserver.bridge.PlayerStatus;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Client of the auth server HTTP API (see {@link PlayerStatus} and {@link AccountApi}). */
final class AuthServerClient {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final Gson gson = new Gson();
    private final String baseUrl;
    private final String secret;

    AuthServerClient(String baseUrl, String secret) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.secret = secret;
    }

    CompletableFuture<PlayerStatus> status(String username) {
        return send(request(username, "").GET(), PlayerStatus.class);
    }

    CompletableFuture<AccountApi.PasswordChangeResponse> changePassword(String username, String oldPassword, String newPassword) {
        var body = gson.toJson(new AccountApi.PasswordChange(oldPassword, newPassword));
        return send(request(username, AccountApi.PASSWORD).POST(HttpRequest.BodyPublishers.ofString(body)),
                AccountApi.PasswordChangeResponse.class);
    }

    CompletableFuture<AccountApi.AccountCommandResponse> accountCommand(String username, String command, List<String> arguments, String locale) {
        var body = gson.toJson(new AccountApi.AccountCommand(command, arguments, locale));
        return send(request(username, AccountApi.COMMAND).POST(HttpRequest.BodyPublishers.ofString(body)),
                AccountApi.AccountCommandResponse.class);
    }

    CompletableFuture<Void> logout(String username) {
        return send(request(username, AccountApi.LOGOUT).POST(HttpRequest.BodyPublishers.noBody()), Void.class);
    }

    private HttpRequest.Builder request(String username, String suffix) {
        var uri = URI.create(baseUrl + PlayerStatus.PATH + URLEncoder.encode(username, StandardCharsets.UTF_8) + suffix);
        return HttpRequest.newBuilder(uri)
                .header(PlayerStatus.SECRET_HEADER, secret)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(5));
    }

    private <T> CompletableFuture<T> send(HttpRequest.Builder request, Class<T> type) {
        return http.sendAsync(request.build(), HttpResponse.BodyHandlers.ofString()).thenApply(response -> {
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("Auth server API answered " + response.statusCode());
            }
            return type == Void.class ? null : gson.fromJson(response.body(), type);
        });
    }
}
