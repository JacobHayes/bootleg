package dev.jacobhayes.bootleg.core.catalog.scrapers.desktop

import dev.jacobhayes.bootleg.core.catalog.Scraper

/** Desktop distros, plus Ubuntu Server and Debian netinst, which share the Ubuntu/Debian scraping logic. */
val desktopScrapers: List<Scraper> =
    ubuntuScrapers +
        listOf(
            DebianLiveScraper(),
            DebianNetinstScraper(),
            FedoraScraper(),
            MintScraper(),
            PopOsScraper(),
            OpenSuseLeapScraper(),
            OpenSuseTumbleweedScraper(),
            ArchLinuxScraper(),
            ManjaroScraper(),
            NixOsScraper(),
            OmarchyScraper(),
        )
