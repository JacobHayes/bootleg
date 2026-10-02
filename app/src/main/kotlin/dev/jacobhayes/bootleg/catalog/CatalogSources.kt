package dev.jacobhayes.bootleg.catalog

import dev.jacobhayes.bootleg.core.catalog.Scraper

/**
 * The scrapers the catalog runs. Production scrapers live in core/catalog
 * (merge point: `desktopScrapers() + serverAndRescueScrapers()`); debug builds add fakes.
 */
fun catalogScrapers(): List<Scraper> = productionScrapers() + debugScrapers()

private fun productionScrapers(): List<Scraper> = emptyList()
