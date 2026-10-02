package dev.jacobhayes.bootleg.catalog

import dev.jacobhayes.bootleg.core.catalog.Scraper
import dev.jacobhayes.bootleg.core.catalog.scrapers.desktop.desktopScrapers
import dev.jacobhayes.bootleg.core.catalog.scrapers.serverAndRescueScrapers

/** The scrapers the catalog runs: every distro scraper from core, plus debug-only extras in debug builds. */
fun catalogScrapers(): List<Scraper> = desktopScrapers + serverAndRescueScrapers + debugScrapers()
