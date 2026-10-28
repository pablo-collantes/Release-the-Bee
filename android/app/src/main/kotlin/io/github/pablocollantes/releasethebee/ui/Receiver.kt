package io.github.pablocollantes.releasethebee.ui

import android.content.Context
import io.github.pablocollantes.releasethebee.api.EntryIndex
import io.github.pablocollantes.releasethebee.api.ReceiverApi
import io.github.pablocollantes.releasethebee.store.SecretStore
import io.github.pablocollantes.releasethebee.store.Settings
import java.io.IOException

/** Where the screens get recordings from: the receiver behind the WebSocket destination. */
object Receiver {

    /** The list, kept for the life of the process so reopening the app only fetches changes. */
    val index = EntryIndex()

    /** Which receiver [index] came from. Another URL means a full fetch, not `?since=`. */
    var indexOrigin: String? = null

    sealed interface Source {
        class Ready(val api: ReceiverApi) : Source
        /** No WebSocket URL set. */
        data object NotSet : Source
        /** Raw TCP carries audio only: there's nothing to ask for recordings. */
        data object TcpOnly : Source
    }

    /** Reads the bearer token from the Keystore: call it off the main thread. */
    fun source(ctx: Context): Source {
        val s = Settings(ctx)
        if (s.sinkKind == Settings.SinkKind.TCP) return Source.TcpOnly
        if (s.sinkKind != Settings.SinkKind.WEBSOCKET) return Source.NotSet
        val base = ReceiverApi.httpBase(s.wsUrl) ?: return Source.NotSet
        return Source.Ready(ReceiverApi(base, SecretStore(ctx).sinkToken))
    }

    /** What went wrong, for a person: a short title, one sentence, and what the button does. */
    class Problem(val title: String, val body: String, val action: Action) {
        enum class Action { SETTINGS, RETRY }
    }

    fun problem(source: Source, error: Throwable? = null): Problem = when (source) {
        Source.NotSet -> Problem(
            "Set up your receiver",
            "Add your receiver's WebSocket URL in Settings. Recordings stream there, and show up here once it has them.",
            Problem.Action.SETTINGS,
        )
        Source.TcpOnly -> Problem(
            "Recordings need a WebSocket receiver",
            "Raw TCP only carries audio. Switch the destination to WebSocket in Settings to see recordings here.",
            Problem.Action.SETTINGS,
        )
        is Source.Ready -> when {
            error is ReceiverApi.HttpError && error.code == 401 -> Problem(
                "The receiver refused the token",
                "Check the bearer token in Settings. It's the same one the receiver was started with.",
                Problem.Action.SETTINGS,
            )
            error is ReceiverApi.HttpError && error.code in listOf(404, 405, 426, 501) -> Problem(
                "This receiver doesn't list recordings",
                "${source.api.origin} takes audio but has no recordings API yet. Update the receiver; audio keeps streaming meanwhile.",
                Problem.Action.RETRY,
            )
            error is ReceiverApi.HttpError -> Problem(
                "The receiver had a problem",
                "${source.api.origin} answered HTTP ${error.code}. Try again in a moment.",
                Problem.Action.RETRY,
            )
            error is IOException || error == null -> Problem(
                "Can't reach your receiver",
                "${source.api.origin} didn't answer. Is it running, and is this phone on its network?",
                Problem.Action.RETRY,
            )
            else -> Problem(
                "The receiver sent something unexpected",
                "${source.api.origin} answered, but not with recordings (${error.javaClass.simpleName}).",
                Problem.Action.RETRY,
            )
        }
    }
}
