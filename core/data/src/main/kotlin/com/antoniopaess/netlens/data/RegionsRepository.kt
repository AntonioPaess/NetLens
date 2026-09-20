package com.antoniopaess.netlens.data

import com.antoniopaess.netlens.data.local.RegionDao
import com.antoniopaess.netlens.data.remote.RegionRemoteDataSource
import com.antoniopaess.netlens.domain.ConnectivitySnapshotProvider
import com.antoniopaess.netlens.domain.RegionDataError
import com.antoniopaess.netlens.domain.RegionDataSource
import com.antoniopaess.netlens.domain.RegionLoadState
import com.antoniopaess.netlens.domain.RegionRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import retrofit2.HttpException
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeoutException
import javax.net.ssl.SSLException

/**
 * Offline-first region repository backed by a Room snapshot and Rest Countries.
 *
 * Every collection reads the cache first. A successful remote refresh replaces
 * the snapshot atomically; a failed refresh leaves an existing snapshot intact
 * and reports the typed failure alongside it. A caller can collect again, or
 * call [retry], to execute a new refresh.
 */
class OfflineFirstRegionRepository(
    private val regionDao: RegionDao,
    private val remote: RegionRemoteDataSource,
    private val connectivity: ConnectivitySnapshotProvider? = null,
    private val latencyCalculator: RegionLatencyCalculator = DeterministicRegionLatency,
) : RegionRepository {
    override fun observeRegions(): Flow<RegionLoadState> =
        flow {
            emit(RegionLoadState.Loading)

            val cached = regionDao.readAll().map { it.toDomain() }
            if (cached.isNotEmpty()) {
                emit(RegionLoadState.Available(cached, RegionDataSource.CACHE))
            }

            if (connectivity?.current()?.available == false) {
                emitRefreshFailure(cached, RegionDataError.NetworkUnavailable)
                return@flow
            }

            try {
                val refreshed = remote.fetch().toDomainRegions(latencyCalculator)
                regionDao.replaceAll(refreshed.map { it.toEntity() })
                emit(RegionLoadState.Available(refreshed, RegionDataSource.REMOTE))
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                emitRefreshFailure(cached, failure.toRegionDataError(connectivity))
            }
        }

    override fun retry(): Flow<RegionLoadState> = observeRegions()

    private suspend fun kotlinx.coroutines.flow.FlowCollector<RegionLoadState>.emitRefreshFailure(
        cached: List<com.antoniopaess.netlens.domain.Region>,
        error: RegionDataError,
    ) {
        if (cached.isNotEmpty()) {
            emit(
                RegionLoadState.Available(
                    regions = cached,
                    source = RegionDataSource.CACHE,
                    refreshError = error,
                ),
            )
        } else {
            emit(RegionLoadState.Unavailable(error))
        }
    }
}

/** Map transport failures to privacy-safe domain categories. */
fun Throwable.toRegionDataError(connectivity: ConnectivitySnapshotProvider? = null): RegionDataError {
    if (connectivity?.current()?.available == false) return RegionDataError.NetworkUnavailable

    return when (rootCause()) {
        is UnknownHostException -> RegionDataError.DnsResolution
        is SSLException -> RegionDataError.Tls
        is HttpException -> RegionDataError.Http((rootCause() as HttpException).code())
        is SocketTimeoutException, is TimeoutException -> RegionDataError.Timeout
        is IOException -> RegionDataError.NetworkUnavailable
        else -> RegionDataError.Unknown
    }
}

private fun Throwable.rootCause(): Throwable {
    var current = this
    while (current.cause != null && current.cause !== current) current = current.cause!!
    return current
}
