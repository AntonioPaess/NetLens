package com.antoniopaess.netlens.domain

/**
 * Supplies wall-clock timestamps for state and event records, injectable so
 * tests can assert metadata without depending on the host clock.
 */
fun interface Clock {
    /** Return the current timestamp in epoch milliseconds. */
    fun now(): Long
}
