package com.cy.utils;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.util.Date;

public class JWTUTILL {

    private static final String SECRET = "BlueAchiveGameDev2026SecretKeyForJWT!!";
    private static final long EXPIRATION = 1000 * 60 * 60 * 24;

    private static SecretKey getKey() {
        return Keys.hmacShaKeyFor(SECRET.getBytes());
    }

    public static String generateToken(Integer id, String username, String identity) {
        return Jwts.builder()
                .subject(username)
                .claim("id", id)
                .claim("identity", identity)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + EXPIRATION))
                .signWith(getKey())
                .compact();
    }

    public static Claims parseToken(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(getKey())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (Exception e) {
            return null;
        }
    }

    public static String getUsername(String token) {
        Claims claims = parseToken(token);
        return claims != null ? claims.getSubject() : null;
    }

    public static Integer getUserId(String token) {
        Claims claims = parseToken(token);
        return claims != null ? claims.get("id", Integer.class) : null;
    }

    public static String getIdentity(String token) {
        Claims claims = parseToken(token);
        return claims != null ? claims.get("identity", String.class) : null;
    }

    public static boolean validateToken(String token) {
        return parseToken(token) != null;
    }
}
