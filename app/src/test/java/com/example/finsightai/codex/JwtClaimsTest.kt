package com.example.finsightai.codex

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JwtClaimsTest {
    @Test fun parsesEmailAccountPlanAndExpiry() {
        val c = JwtClaims.parse(idTokenFor(email = "a@b.co", account = "acc-9", plan = "pro", exp = 1234))
        assertEquals("a@b.co", c.email)
        assertEquals("acc-9", c.accountId)
        assertEquals("pro", c.planType)
        assertEquals(1234L, c.expiresAtSeconds)
    }

    @Test fun missingClaimsAreNull() {
        val c = JwtClaims.parse(fakeJwt("""{"sub":"x"}"""))
        assertNull(c.email); assertNull(c.accountId); assertNull(c.planType); assertNull(c.expiresAtSeconds)
    }

    @Test fun profileClaimEmailIsFallback() {
        val c = JwtClaims.parse(fakeJwt("""{"https://api.openai.com/profile":{"email":"p@q.io"}}"""))
        assertEquals("p@q.io", c.email)
    }

    @Test fun garbageAndNullDoNotThrow() {
        listOf(null, "", "abc", "a.b.c", "a.!!!.c", "a.${java.util.Base64.getUrlEncoder().encodeToString("[1,2]".toByteArray())}.c")
            .forEach { assertEquals(JwtClaims(), JwtClaims.parse(it)) }
    }

    @Test fun toleratesPaddedPayload() {
        val padded = java.util.Base64.getUrlEncoder().encodeToString("""{"email":"x@y.z"}""".toByteArray())
        assertEquals("x@y.z", JwtClaims.parse("h.$padded.s").email)
    }

    @Test fun tokensToStringNeverContainsSecrets() {
        val t = CodexTokens(idToken = "ID", accessToken = "ACCESS-SECRET", refreshToken = "REFRESH-SECRET", email = "e@x")
        assertEquals(false, t.toString().contains("SECRET"))
        assertEquals(false, AccessCreds("ACCESS-SECRET", "a").toString().contains("SECRET"))
    }
}
