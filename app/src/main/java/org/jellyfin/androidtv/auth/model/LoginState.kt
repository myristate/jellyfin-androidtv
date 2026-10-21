package org.jellyfin.androidtv.auth.model

import org.jellyfin.androidtv.util.apiclient.ServerRefusal
import org.jellyfin.sdk.api.client.exception.ApiClientException

sealed class LoginState
data object AuthenticatingState : LoginState()
data object RequireSignInState : LoginState()
data object RequirePinState : LoginState()
data object InvalidPinState : LoginState()
data object ServerUnavailableState : LoginState()
data class ServerVersionNotSupported(val server: Server) : LoginState()
data class ApiClientErrorLoginState(val error: ApiClientException) : LoginState()
data object AuthenticatedState : LoginState()

/**
 * The server refused the sign in for a reason the person should be told (Finly), such as too many wrong PINs or a
 * password needed away from home. [status] is the HTTP status.
 */
data class RefusedLoginState(val refusal: ServerRefusal?, val status: Int) : LoginState()
