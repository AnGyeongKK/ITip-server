package com.gkk.itip.api.auth;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "itip.auth.mode=supabase")
@AutoConfigureMockMvc
@Testcontainers
class SupabaseGoogleAuthTest {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));
    private static final String ME = "/api/v1/users/me";
    private static final String FOLDERS = "/api/v1/folders";
    private static HttpServer jwks;
    private static RSAKey rsa;
    private static ECKey ec;
    private static String projectUrl;

    @Autowired
    private MockMvc mvc;

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry properties) throws Exception {
        rsa = new RSAKeyGenerator(2048).keyID("rsa-test").generate();
        ec = new ECKeyGenerator(Curve.P_256).keyID("ec-test").generate();
        jwks = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        projectUrl = "http://127.0.0.1:" + jwks.getAddress().getPort();
        byte[] publicKeys = new JWKSet(List.of(rsa.toPublicJWK(), ec.toPublicJWK())).toString()
                .getBytes(StandardCharsets.UTF_8);
        jwks.createContext("/auth/v1/.well-known/jwks.json", exchange -> {
            try (exchange) {
                exchange.getResponseHeaders().set(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
                exchange.sendResponseHeaders(200, publicKeys.length);
                exchange.getResponseBody().write(publicKeys);
            }
        });
        jwks.start();
        properties.add("itip.auth.supabase-url", () -> projectUrl + "/");
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @AfterAll
    static void stopJwks() {
        if (jwks != null) jwks.stop(0);
    }

    @Test
    void returnsSubjectFromSupabaseAccessTokenForBothSigningAlgorithms() throws Exception {
        var user = UUID.randomUUID().toString();
        for (JWSAlgorithm algorithm : List.of(JWSAlgorithm.RS256, JWSAlgorithm.ES256)) {
            mvc.perform(get(ME).header(HttpHeaders.AUTHORIZATION, bearer(claims(user).build(), algorithm)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.userId").value(user));
        }
    }

    @Test
    void rejectsMissingOrInvalidCredentialsWithJsonEvenForStreamingAcceptHeader() throws Exception {
        mvc.perform(get(ME).accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
        String id = UUID.randomUUID().toString();
        List<JWTClaimsSet> invalidClaims = List.of(
                claims(id).issuer("https://wrong-project.invalid/auth/v1").build(),
                claims(id).audience("anon").build(),
                claims(id).expirationTime(Date.from(Instant.now().minusSeconds(120))).build(),
                claims(id).expirationTime(null).build(),
                claims(id).notBeforeTime(Date.from(Instant.now().plusSeconds(120))).build(),
                claims(id).claim("role", "service_role").build(),
                claims(id).claim("is_anonymous", true).build(),
                claims("not-a-uuid").build());
        for (var invalid : invalidClaims) {
            mvc.perform(get(ME).header(HttpHeaders.AUTHORIZATION, bearer(invalid, JWSAlgorithm.RS256)))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
        }
        var wrongSigner = new RSAKeyGenerator(2048).keyID(rsa.getKeyID()).generate();
        var forged = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(rsa.getKeyID()).build(), claims(id).build());
        forged.sign(new RSASSASigner(wrongSigner));
        mvc.perform(get(ME).header(HttpHeaders.AUTHORIZATION, "Bearer " + forged.serialize()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    void usesAuthenticatedSubjectForExistingOwnerChecks() throws Exception {
        String owner = bearer(claims(UUID.randomUUID().toString()).build(), JWSAlgorithm.RS256);
        String other = bearer(claims(UUID.randomUUID().toString()).build(), JWSAlgorithm.RS256);
        var created = mvc.perform(post(FOLDERS).header(HttpHeaders.AUTHORIZATION, owner)
                        .header("Idempotency-Key", UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"개인 폴더\"}"))
                .andExpect(status().isCreated()).andReturn();
        var location = created.getResponse().getHeader(HttpHeaders.LOCATION);
        mvc.perform(get(location).header(HttpHeaders.AUTHORIZATION, owner)).andExpect(status().isOk());
        mvc.perform(get(location).header(HttpHeaders.AUTHORIZATION, other))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void nextAccountCanRegisterDeviceAfterPreviousOwnerDisconnectsIt() throws Exception {
        String owner = bearer(claims(UUID.randomUUID().toString()).build(), JWSAlgorithm.ES256);
        String nextOwner = bearer(claims(UUID.randomUUID().toString()).build(), JWSAlgorithm.ES256);
        String device = ME + "/devices/" + UUID.randomUUID();
        String registration = """
                {"platform":"ANDROID","fcmToken":"synthetic-test-token","notificationPermission":"GRANTED"}
                """;

        mvc.perform(put(device).header(HttpHeaders.AUTHORIZATION, owner)
                        .contentType(MediaType.APPLICATION_JSON).content(registration))
                .andExpect(status().isOk());
        mvc.perform(delete(device).header(HttpHeaders.AUTHORIZATION, nextOwner))
                .andExpect(status().isOk());
        mvc.perform(put(device).header(HttpHeaders.AUTHORIZATION, nextOwner)
                        .contentType(MediaType.APPLICATION_JSON).content(registration))
                .andExpect(status().isConflict());
        mvc.perform(delete(device).header(HttpHeaders.AUTHORIZATION, owner))
                .andExpect(status().isOk());
        mvc.perform(put(device).header(HttpHeaders.AUTHORIZATION, nextOwner)
                        .contentType(MediaType.APPLICATION_JSON).content(registration))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.registered").value(true));
    }

    private static JWTClaimsSet.Builder claims(String subject) {
        return new JWTClaimsSet.Builder().issuer(projectUrl + "/auth/v1").audience("authenticated")
                .subject(subject).issueTime(Date.from(Instant.now())).expirationTime(Date.from(Instant.now().plusSeconds(300)))
                .claim("role", "authenticated").claim("is_anonymous", false);
    }

    private static String bearer(JWTClaimsSet claims, JWSAlgorithm algorithm) throws JOSEException {
        boolean elliptic = JWSAlgorithm.ES256.equals(algorithm);
        var token = new SignedJWT(new JWSHeader.Builder(algorithm).keyID(elliptic ? ec.getKeyID() : rsa.getKeyID()).build(), claims);
        token.sign(elliptic ? new ECDSASigner(ec) : new RSASSASigner(rsa));
        return "Bearer " + token.serialize();
    }
}
