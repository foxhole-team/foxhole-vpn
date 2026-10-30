package com.foxhole.vpn.review

import android.app.Application
import com.foxhole.vpn.router.review.RouterReviewBackend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/** Separate demo application: never constructs the production application graph. */
class ReviewApplication : Application() {
    private val reviewScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    internal val controller = ReviewVpnController()
    private val backend = lazy { RouterReviewBackend(reviewScope, initiallyInstalled = false) }
    internal val routerBackend get() = backend.value

    override fun onTerminate() {
        if (backend.isInitialized()) backend.value.close()
        reviewScope.cancel()
        super.onTerminate()
    }
}
