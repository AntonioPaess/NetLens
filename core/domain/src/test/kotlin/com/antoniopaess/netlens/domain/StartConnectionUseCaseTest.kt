package com.antoniopaess.netlens.domain

import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

class StartConnectionUseCaseTest {
    @Test
    fun `dispatches start through repository`() =
        runTest {
            val repository = mockk<ConnectionRepository>()
            val region = Region(name = "Test Region", code = "TR", simulatedLatency = 20L)
            coEvery { repository.dispatch(ConnectionCommand.Start(region)) } just Runs

            StartConnectionUseCase(repository)(region)

            coVerify(exactly = 1) { repository.dispatch(ConnectionCommand.Start(region)) }
        }
}
