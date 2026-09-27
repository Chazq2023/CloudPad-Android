// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.cloudplay.model

import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression coverage for [CloudError.fromMessage]'s keyword classifier. This matters more than
 *  a typical string-matching helper: CloudPlayFragment.handleAuthenticationError() reacts to an
 *  [CloudError.AuthenticationError] by wiping the stored NPSSO token and forcing the user back
 *  through the login flow, so a false positive here doesn't just pick the wrong dialog — it
 *  destroys a working login. See the classifier's own comment for the incident this covers: the
 *  keyword list used to include "failed", "login", "token" and "oauth", generic enough to match
 *  almost any ordinary network/timeout/parsing failure surfaced via the catalog's catch-all
 *  "Unexpected error: ${e.message}" path, which could clear a perfectly valid token on a plain
 *  network hiccup and re-show "Login Required" — repeatedly, since each retry could fail the same
 *  generic way again. */
class CloudErrorTest {

	@Test
	fun `the real AuthorizationFailedException message is still classified as an auth error`() {
		// Exact wording from CloudStreamingBackend.checkAuthorization()'s failure path.
		val error = CloudError.fromMessage("Your NPSSO token is likely expired. Please re-login to continue using cloud streaming.")
		assertTrue(error is CloudError.AuthenticationError)
	}

	@Test
	fun `explicit unauthorized and forbidden responses are classified as auth errors`() {
		assertTrue(CloudError.fromMessage("Authorization check failed: 401 Unauthorized") is CloudError.AuthenticationError)
		assertTrue(CloudError.fromMessage("Request failed with status 403 Forbidden") is CloudError.AuthenticationError)
	}

	@Test
	fun `a generic network failure is not misclassified as an auth error`() {
		// Realistic shape of the catalog loader's catch-all: "Unexpected error: ${e.message}"
		// wrapping a plain IOException/SocketTimeoutException-style message. None of these should
		// ever wipe a valid NPSSO token.
		val messages = listOf(
			"Unexpected error: Failed to connect to ca.account.sony.com",
			"Unexpected error: connect timed out",
			"Unexpected error: Unable to resolve host \"store.playstation.com\"",
			"Unexpected error: Software caused connection abort",
			"Request failed"
		)
		for (message in messages) {
			val error = CloudError.fromMessage(message)
			assertTrue("expected '$message' to NOT be an AuthenticationError", error !is CloudError.AuthenticationError)
		}
	}

	@Test
	fun `a plain parsing or null-pointer style failure is not misclassified as an auth error`() {
		val messages = listOf(
			"Unexpected error: null",
			"Unexpected error: Expected BEGIN_OBJECT but was STRING at line 1 column 1",
			"Unexpected error: Index 0 out of bounds for length 0"
		)
		for (message in messages) {
			val error = CloudError.fromMessage(message)
			assertTrue("expected '$message' to NOT be an AuthenticationError", error !is CloudError.AuthenticationError)
		}
	}

	@Test
	fun `network-shaped messages classify as NetworkError`() {
		assertTrue(CloudError.fromMessage("Unexpected error: connection timeout") is CloudError.NetworkError)
		assertTrue(CloudError.fromMessage("No internet connection") is CloudError.NetworkError)
	}
}
