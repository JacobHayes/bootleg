package dev.jacobhayes.bootleg.core.catalog.scrapers

import dev.jacobhayes.bootleg.core.catalog.Scraper
import dev.jacobhayes.bootleg.core.catalog.scrapers.rescue.NetbootXyzScraper
import dev.jacobhayes.bootleg.core.catalog.scrapers.rescue.ShredOsScraper
import dev.jacobhayes.bootleg.core.catalog.scrapers.rescue.SystemRescueScraper
import dev.jacobhayes.bootleg.core.catalog.scrapers.rescue.clonezillaScraper
import dev.jacobhayes.bootleg.core.catalog.scrapers.rescue.gpartedScraper
import dev.jacobhayes.bootleg.core.catalog.scrapers.server.AlpineScraper
import dev.jacobhayes.bootleg.core.catalog.scrapers.server.ProxmoxScraper
import dev.jacobhayes.bootleg.core.catalog.scrapers.server.TrueNasScraper
import dev.jacobhayes.bootleg.core.catalog.scrapers.server.almaLinuxScraper
import dev.jacobhayes.bootleg.core.catalog.scrapers.server.rockyScraper

/**
 * Server and rescue scrapers. Memtest86+ is absent on purpose: upstream publishes only zipped
 * ISOs (`*.iso.zip`), and a Release has no way to say "unpack before use".
 */
val serverAndRescueScrapers: List<Scraper> =
    listOf(
        AlpineScraper(),
        rockyScraper,
        almaLinuxScraper,
        ProxmoxScraper(),
        TrueNasScraper(),
        SystemRescueScraper(),
        gpartedScraper,
        clonezillaScraper,
        ShredOsScraper(),
        NetbootXyzScraper(),
    )
