package com.antoniopaess.netlens.data

import com.antoniopaess.netlens.data.local.RegionDao
import com.antoniopaess.netlens.data.local.RegionEntity
import com.antoniopaess.netlens.data.remote.CountryDto
import com.antoniopaess.netlens.data.remote.CountryNameDto
import com.antoniopaess.netlens.data.remote.RegionRemoteDataSource
import com.antoniopaess.netlens.domain.RegionDataError
import com.antoniopaess.netlens.domain.RegionDataSource
import com.antoniopaess.netlens.domain.RegionLoadState
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType
import okhttp3.ResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeoutException
import javax.net.ssl.SSLException

class OfflineFirstRegionRepositoryTest {
    @Test
    fun remoteSuccessPersistsAndEmitsRemoteSnapshot() =
        runTest {
            val dao = FakeRegionDao()
            val remote =
                RegionRemoteDataSource {
                    listOf(CountryDto(CountryNameDto("Brazil"), "br"))
                }

            val states = OfflineFirstRegionRepository(dao, remote).observeRegions().toList()

            assertEquals(2, states.size)
            assertEquals(RegionLoadState.Loading, states[0])
            val remoteState = states[1] as RegionLoadState.Available
            assertEquals(RegionDataSource.REMOTE, remoteState.source)
            assertEquals("BR", remoteState.regions.single().code)
            assertEquals(remoteState.regions.single(), dao.rows.single().toDomain())
        }

    @Test
    fun cacheIsEmittedBeforeSuccessfulRefresh() =
        runTest {
            val cached = RegionEntity("CA", "Canada", 91L)
            val dao = FakeRegionDao(mutableListOf(cached))
            val remote =
                RegionRemoteDataSource {
                    listOf(CountryDto(CountryNameDto("Brazil"), "BR"))
                }

            val states = OfflineFirstRegionRepository(dao, remote).observeRegions().toList()

            assertEquals(3, states.size)
            assertEquals(RegionDataSource.CACHE, (states[1] as RegionLoadState.Available).source)
            assertEquals(RegionDataSource.REMOTE, (states[2] as RegionLoadState.Available).source)
            assertEquals("BR", dao.rows.single().code)
        }

    @Test
    fun remoteFailureKeepsUsableCacheAndReportsTypedError() =
        runTest {
            val dao = FakeRegionDao(mutableListOf(RegionEntity("CA", "Canada", 91L)))
            val remote = RegionRemoteDataSource { throw IOException("offline") }

            val states = OfflineFirstRegionRepository(dao, remote).observeRegions().toList()

            assertEquals(3, states.size)
            val failureState = states.last() as RegionLoadState.Available
            assertEquals(RegionDataSource.CACHE, failureState.source)
            assertEquals(RegionDataError.NetworkUnavailable, failureState.refreshError)
            assertEquals("CA", dao.rows.single().code)
        }

    @Test
    fun remoteFailureWithoutCacheCanBeRetried() =
        runTest {
            val dao = FakeRegionDao()
            var attempts = 0
            val remote =
                RegionRemoteDataSource {
                    attempts += 1
                    if (attempts == 1) throw IOException("offline")
                    listOf(CountryDto(CountryNameDto("Chile"), "CL"))
                }
            val repository = OfflineFirstRegionRepository(dao, remote)

            val first = repository.observeRegions().toList()
            val second = repository.retry().toList()

            assertTrue(first.last() is RegionLoadState.Unavailable)
            assertEquals(RegionDataSource.REMOTE, (second.last() as RegionLoadState.Available).source)
            assertEquals(2, attempts)
        }

    @Test
    fun remoteFailuresMapToTypedCategories() {
        assertEquals(RegionDataError.DnsResolution, UnknownHostException().toRegionDataError())
        assertEquals(RegionDataError.Tls, SSLException("handshake").toRegionDataError())
        assertEquals(
            RegionDataError.Http(503),
            HttpException(
                Response.error<String>(
                    503,
                    ResponseBody.create(MediaType.parse("text/plain"), "unavailable"),
                ),
            ).toRegionDataError(),
        )
        assertEquals(RegionDataError.Timeout, SocketTimeoutException().toRegionDataError())
        assertEquals(RegionDataError.Timeout, TimeoutException().toRegionDataError())
    }

    private class FakeRegionDao(
        var rows: MutableList<RegionEntity> = mutableListOf(),
    ) : RegionDao {
        override suspend fun readAll(): List<RegionEntity> = rows.toList()

        override suspend fun deleteAll() {
            rows.clear()
        }

        override suspend fun insertAll(regions: List<RegionEntity>) {
            rows.addAll(regions)
        }
    }
}
