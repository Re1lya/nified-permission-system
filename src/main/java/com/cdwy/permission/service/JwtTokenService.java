package com.cdwy.permission.service;

import com.cdwy.permission.entity.LoginSession;
import com.cdwy.permission.entity.UserAccount;
import com.cdwy.permission.exception.AuthenticationFailedException;
import com.cdwy.permission.repository.LoginSessionRepository;
import com.cdwy.permission.repository.UserAccountRepository;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class JwtTokenService {

    static final long IDLE_SECONDS = 1800;

    private final SecretKey signingKey;
    private final long expirationSeconds;
    private final UserAccountRepository userRepository;
    private final LoginSessionRepository sessionRepository;
    private final com.cdwy.permission.platform.PlatformStore platform;

    public JwtTokenService(
            @Value("${permission.jwt.secret}") String secret,
            @Value("${permission.jwt.expiration-seconds:7200}") long expirationSeconds,
            UserAccountRepository userRepository,
            LoginSessionRepository sessionRepository, com.cdwy.permission.platform.PlatformStore platform) {
        byte[] secretBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (secretBytes.length > 0 && secretBytes.length < 32) {
            throw new IllegalArgumentException("permission.jwt.secret 至少需要 32 个字节");
        }
        if (expirationSeconds <= 0) {
            throw new IllegalArgumentException("permission.jwt.expiration-seconds 必须大于 0");
        }
        this.signingKey = secretBytes.length == 0 ? Jwts.SIG.HS256.key().build() : Keys.hmacShaKeyFor(secretBytes);
        this.expirationSeconds = expirationSeconds;
        this.userRepository = userRepository;
        this.sessionRepository = sessionRepository;
        this.platform = platform;
    }

    @Transactional
    public String issue(Long userId) {
        // The same row is locked by login, password reset, disable and delete.
        UserAccount user = userRepository.findByIdForUpdate(userId)
                .filter(UserAccount::isEnabled)
                .orElseThrow(JwtTokenService::invalidToken);
        Instant issuedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant expiresAt = issuedAt.plusSeconds(expirationSeconds);
        String tokenId = UUID.randomUUID().toString();
        String token = Jwts.builder()
                .id(tokenId)
                .claim("permission_version", platform.version(userId))
                .subject(userId.toString())
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(expiresAt))
                .signWith(signingKey)
                .compact();
        sessionRepository.save(new LoginSession(tokenId, user, issuedAt, expiresAt));
        return token;
    }

    @Transactional
    public Long requireUserId(String authorizationHeader) {
        Claims claims = parse(extractToken(authorizationHeader));
        Long userId = subject(claims);
        platform.validAccount(userId);
        Number version=claims.get("permission_version",Number.class);
        if(version==null||version.longValue()!=platform.version(userId))throw new AuthenticationFailedException("权限版本已变化，请刷新令牌");
        LoginSession session = sessionRepository.findByIdAndUser_Id(claims.getId(), userId)
                .orElseThrow(JwtTokenService::invalidToken);
        if (!session.getUser().isEnabled()
                || !session.getExpiresAt().isAfter(Instant.now())
                || !session.getIssuedAt().equals(claims.getIssuedAt().toInstant())
                || !session.getExpiresAt().equals(claims.getExpiration().toInstant())) {
            throw invalidToken();
        }
        if(lastSeen(claims.getId(),session.getIssuedAt()).plusSeconds(IDLE_SECONDS).isBefore(Instant.now()))throw invalidToken();
        platform.db().update("insert into session_activity(session_id,last_seen) select ?,? where not exists(select 1 from session_activity where session_id=?)",claims.getId(),Instant.now(),claims.getId());
        platform.db().update("update session_activity set last_seen=? where session_id=?",Instant.now(),claims.getId());
        return userId;
    }

    @Transactional
    public void revoke(String authorizationHeader) {
        if (authorizationHeader == null || authorizationHeader.isBlank()) {
            return;
        }
        try {
            Claims claims = parse(extractToken(authorizationHeader));
            sessionRepository.deleteSession(claims.getId(), subject(claims));
        } catch (AuthenticationFailedException ignored) {
            // Logging out with an expired or malformed token is already complete.
        }
    }

    /** The user behind a still-valid bearer, or null; used to attribute logout without failing it. */
    @Transactional
    public Long subjectIfValid(String authorizationHeader) {
        try {
            return requireUserId(authorizationHeader);
        } catch (AuthenticationFailedException exception) {
            return null;
        }
    }

    @Transactional
    public void revokeAll(Long userId) {
        // Account commands hold the user lock until both changes commit.
        sessionRepository.deleteAllForUser(userId);
        platform.db().update("delete from oauth2_authorization where principal_name=?",userId.toString());
        platform.changed(userId);
    }

    /**
     * Rotate a platform JWT. The presented token may already be expired, but its session must still exist,
     * the account must be usable and the last activity must lie within the 30-minute idle window (BR-17).
     */
    @Transactional
    public String refresh(String authorizationHeader) {
        Claims claims = parse(extractToken(authorizationHeader), IDLE_SECONDS);
        Long userId = subject(claims);
        platform.validAccount(userId);
        LoginSession session = sessionRepository.findByIdAndUser_Id(claims.getId(), userId)
                .orElseThrow(JwtTokenService::invalidToken);
        if (!session.getUser().isEnabled() || !session.getIssuedAt().equals(claims.getIssuedAt().toInstant())) {
            throw invalidToken();
        }
        if (lastSeen(claims.getId(), session.getIssuedAt()).plusSeconds(IDLE_SECONDS).isBefore(Instant.now())) {
            throw invalidToken();
        }
        sessionRepository.deleteSession(claims.getId(), userId);
        return issue(userId);
    }

    private Instant lastSeen(String sessionId, Instant issuedAt) {
        var activity = platform.rows("select last_seen from session_activity where session_id=?", sessionId);
        Object stored = activity.isEmpty() ? null : activity.getFirst().get("last_seen");
        return stored == null ? issuedAt : stored instanceof java.time.OffsetDateTime offset ? offset.toInstant() : ((java.sql.Timestamp) stored).toInstant();
    }

    public String sessionId(String authorizationHeader){return parse(extractToken(authorizationHeader)).getId();}
    public Instant issuedAt(String authorizationHeader){return parse(extractToken(authorizationHeader)).getIssuedAt().toInstant();}

    private Claims parse(String token) {
        return parse(token, 0);
    }

    private Claims parse(String token, long allowedExpirySeconds) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .clockSkewSeconds(allowedExpirySeconds)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            if (claims.getId() == null || claims.getId().isBlank()
                    || claims.getIssuedAt() == null || claims.getExpiration() == null
                    || claims.getIssuedAt().after(new Date())
                    || !claims.getExpiration().after(claims.getIssuedAt())) {
                throw invalidToken();
            }
            return claims;
        } catch (JwtException | IllegalArgumentException exception) {
            throw invalidToken();
        }
    }

    private Long subject(Claims claims) {
        try {
            long userId = Long.parseLong(claims.getSubject());
            if (userId <= 0) {
                throw invalidToken();
            }
            return userId;
        } catch (JwtException | IllegalArgumentException exception) {
            throw invalidToken();
        }
    }

    private String extractToken(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.regionMatches(true, 0, "Bearer ", 0, 7)) {
            throw invalidToken();
        }
        String token = authorizationHeader.substring("Bearer ".length()).trim();
        if (token.isEmpty()) {
            throw invalidToken();
        }
        return token;
    }

    private static AuthenticationFailedException invalidToken() {
        return new AuthenticationFailedException("登录状态无效或已过期，请重新登录");
    }
}
