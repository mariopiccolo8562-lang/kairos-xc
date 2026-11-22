package com.kairos.xc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** «Dove vederla» con le guide TV pubbliche (23 novembre 2026): nomi dei canali puliti uguali al pannello e a Kairos Pro,
 *  nomi italiani delle squadre (aka) e livello «probabile». Squadre, canali e programmi inventati. */
class EventFontiTest {
    private val kick = 1_795_000_000_000L
    private val bayern = SportEvent("calcio:3", "calcio", "German Bundesliga", kick, false,
        home = EventTeam("Bayern Munich", aka = listOf("bayern", "bayern monaco")), away = EventTeam("Werder Bremen", aka = listOf("werder", "brema")))
    private val liv = SportEvent("calcio:4", "calcio", "English Premier League", kick, false, home = EventTeam("Liverpool"), away = EventTeam("Brighton & Hove Albion"))
    private val como = SportEvent("calcio:5", "calcio", "Italian Serie A", kick, false, home = EventTeam("Como"), away = EventTeam("Genoa"))

    @Test fun nomiDeiCanaliComeIlPannello() {
        val cases = listOf(
            "IT: Sky Sports Uno FHD" to "sky sport 1",
            "Sky Sport Calcio Full HD" to "sky sport calcio",
            "(SD)Sky Sport Calcio" to "sky sport calcio",
            "Sky Sport Calcio RAW" to "sky sport calcio",
            "Sky Sport  251" to "sky sport 251",
            "Sky Sport HD 252" to "sky sport 252",
            "DAZN 1" to "dazn 1",
            "ITA | DAZN 1 4K" to "dazn 1",
            "RAI SPORT H265" to "rai sport",
            "Rai Sport HD H265 (DTT)" to "rai sport",
            "Sky Sport Arena FHD (H265)" to "sky sport arena",
            "Rai 1 HD  101" to "rai 1",
            "RAI Sport   227" to "rai sport",
            "Sky Sport Max  205" to "sky sport max",
            "Sky Sport1" to "sky sport 1",
            "Sky Sport 1 ABR" to "sky sport 1",
            "Eurosport 1HD" to "eurosport 1",
            "Eurosport2 Italia" to "eurosport 2",
            "Canale5HD" to "canale 5",
            "Sky Sport24" to "sky sport 24",
            "Premium Eurosport 1" to "eurosport 1",
            "Sky Sport Uno" to "sky sport 1",
            "Italia 1" to "italia 1",
            "Italia1 HD" to "italia 1",
            "Sky Eurosport 1" to "eurosport 1",
            "TV8 HD" to "tv8",
            "Sky Sport MotoGP 4K" to "sky sport motogp",
            "Sky Sport F1 UHD" to "sky sport f1",
            "SKY SPORT ARENA HDR PLUS" to "sky sport arena",
            "Sky Sport 24 SuperHD" to "sky sport 24",
            "Rai Sport Plus HD" to "rai sport",
            "SKY Sport 24 (DSL Lente)" to "sky sport 24",
            "ITALIA – Rai 2 HD" to "rai 2",
            "Sky Sport Mix 211" to "sky sport mix"
        )
        for ((name, want) in cases) assertEquals(name, want, EventMatch.chKey(name))
        assertEquals("", EventMatch.chKey("Sky Sport 24 +1HD")) // differita
        assertEquals("", EventMatch.chKey("DAZN 1 +1"))
    }

    @Test fun nomiItalianiDelleSquadre() {
        assertTrue(EventMatch.titleHit(bayern, "Bayern Monaco - Werder Brema"))
        assertFalse(EventMatch.titleHit(bayern, "Bayern Monaco, le ultime notizie"))
    }

    @Test fun probabile() {
        val l = EventMatch.halfHit(liv)!!
        assertTrue(l("Liverpool, la sfida di Anfield"))
        assertFalse(l("Liverpool news")) // notizie
        assertFalse(l("Premier League Magazine")) // rubrica
        val c = EventMatch.halfHit(como)!!
        assertTrue(c("Calcio: Serie A")) // il campionato, programma generico
        assertFalse(c("Serie A: Lazio - Lecce")) // un'altra partita dello stesso campionato
        assertTrue(EventMatch.clubChannel("Inter TV HD"))
        assertFalse(EventMatch.clubChannel("Sky Sport Calcio"))
    }
}
