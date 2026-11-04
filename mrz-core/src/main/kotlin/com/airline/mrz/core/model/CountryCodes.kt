package com.airline.mrz.core.model

/**
 * Three-letter codes accepted in the issuing state and nationality fields.
 *
 * ICAO Doc 9303 Part 3 uses ISO 3166-1 alpha-3 plus a set of ICAO-specific codes
 * (Germany's `D`, the British nationality variants, UN and stateless codes, and
 * others). Codes shorter than three characters are padded with fillers on the
 * document, so `D<<` is the on-document form of `D`.
 */
public object CountryCodes {

    /** ISO 3166-1 alpha-3, as of 2025. */
    public val ISO_3166_ALPHA3: Set<String> = setOf(
        "ABW", "AFG", "AGO", "AIA", "ALA", "ALB", "AND", "ARE", "ARG", "ARM", "ASM", "ATA", "ATF", "ATG",
        "AUS", "AUT", "AZE", "BDI", "BEL", "BEN", "BES", "BFA", "BGD", "BGR", "BHR", "BHS", "BIH", "BLM",
        "BLR", "BLZ", "BMU", "BOL", "BRA", "BRB", "BRN", "BTN", "BVT", "BWA", "CAF", "CAN", "CCK", "CHE",
        "CHL", "CHN", "CIV", "CMR", "COD", "COG", "COK", "COL", "COM", "CPV", "CRI", "CUB", "CUW", "CXR",
        "CYM", "CYP", "CZE", "DEU", "DJI", "DMA", "DNK", "DOM", "DZA", "ECU", "EGY", "ERI", "ESH", "ESP",
        "EST", "ETH", "FIN", "FJI", "FLK", "FRA", "FRO", "FSM", "GAB", "GBR", "GEO", "GGY", "GHA", "GIB",
        "GIN", "GLP", "GMB", "GNB", "GNQ", "GRC", "GRD", "GRL", "GTM", "GUF", "GUM", "GUY", "HKG", "HMD",
        "HND", "HRV", "HTI", "HUN", "IDN", "IMN", "IND", "IOT", "IRL", "IRN", "IRQ", "ISL", "ISR", "ITA",
        "JAM", "JEY", "JOR", "JPN", "KAZ", "KEN", "KGZ", "KHM", "KIR", "KNA", "KOR", "KWT", "LAO", "LBN",
        "LBR", "LBY", "LCA", "LIE", "LKA", "LSO", "LTU", "LUX", "LVA", "MAC", "MAF", "MAR", "MCO", "MDA",
        "MDG", "MDV", "MEX", "MHL", "MKD", "MLI", "MLT", "MMR", "MNE", "MNG", "MNP", "MOZ", "MRT", "MSR",
        "MTQ", "MUS", "MWI", "MYS", "MYT", "NAM", "NCL", "NER", "NFK", "NGA", "NIC", "NIU", "NLD", "NOR",
        "NPL", "NRU", "NZL", "OMN", "PAK", "PAN", "PCN", "PER", "PHL", "PLW", "PNG", "POL", "PRI", "PRK",
        "PRT", "PRY", "PSE", "PYF", "QAT", "REU", "ROU", "RUS", "RWA", "SAU", "SDN", "SEN", "SGP", "SGS",
        "SHN", "SJM", "SLB", "SLE", "SLV", "SMR", "SOM", "SPM", "SRB", "SSD", "STP", "SUR", "SVK", "SVN",
        "SWE", "SWZ", "SXM", "SYC", "SYR", "TCA", "TCD", "TGO", "THA", "TJK", "TKL", "TKM", "TLS", "TON",
        "TTO", "TUN", "TUR", "TUV", "TWN", "TZA", "UGA", "UKR", "UMI", "URY", "USA", "UZB", "VAT", "VCT",
        "VEN", "VGB", "VIR", "VNM", "VUT", "WLF", "WSM", "YEM", "ZAF", "ZMB", "ZWE",
    )

    /**
     * Codes defined by ICAO Doc 9303 Part 3 that are not ISO 3166-1 alpha-3, in their
     * on-document form (padded with `<` to three characters where needed).
     */
    public val ICAO_SPECIAL: Set<String> = setOf(
        "D<<", // Germany
        "GBD", // British Overseas Territories Citizen
        "GBN", // British National (Overseas)
        "GBO", // British Overseas Citizen
        "GBP", // British Protected Person
        "GBS", // British Subject
        "EUE", // European Union
        "UNO", // United Nations Organization
        "UNA", // United Nations specialized agency
        "UNK", // Kosovo resident under UN interim administration (UNMIK)
        "RKS", // Republic of Kosovo
        "XBA", // African Development Bank
        "XIM", // African Export-Import Bank
        "XCC", // Caribbean Community
        "XCO", // Common Market for Eastern and Southern Africa
        "XEC", // Economic Community of West African States
        "XPO", // International Criminal Police Organization (INTERPOL)
        "XES", // Organization of Eastern Caribbean States
        "XOM", // Sovereign Military Order of Malta
        "XXA", // Stateless person
        "XXB", // Refugee (1951 Convention)
        "XXC", // Refugee (other)
        "XXX", // Unspecified nationality
        "ANT", // Netherlands Antilles (legacy, still on documents in circulation)
        "NTZ", // Neutral Zone (legacy)
        "ZIM", // Zimbabwe (legacy ICAO form, seen on older documents)
        "UTO", // Utopia: ICAO specimen documents and test data
    )

    public val ALL: Set<String> = ISO_3166_ALPHA3 + ICAO_SPECIAL

    /** True when [code], as read from the document (three characters), is acceptable. */
    public fun isValid(code: String): Boolean = code.length == 3 && code in ALL

    /** Human-friendly form: trailing fillers removed (`D<<` becomes `D`). */
    public fun canonical(code: String): String = code.trimEnd(MrzAlphabet.FILLER)
}
