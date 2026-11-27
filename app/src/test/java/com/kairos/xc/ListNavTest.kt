package com.kairos.xc

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Telecomando nelle schermate «categorie a sinistra, lista a destra» (27 novembre 2026, il titolare: «vado su una categoria,
 * scorro la lista dei film o serie o canali e mi rimanda nella lista principale»).
 */
class ListNavTest {
    @Test fun giuNonEsceMaiDallaLista() {
        // c'è un altro elemento della lista: fa Android
        assertEquals(ListNav.MOVE, ListNav.vertical(up = false, firstRow = false, target = ListNav.INSIDE))
        // in fondo alla categoria Android troverebbe una categoria a sinistra, un pulsante o niente: il cursore resta dov'è
        for (t in intArrayOf(ListNav.CATS, ListNav.OTHER, ListNav.NONE)) {
            assertEquals(ListNav.STAY, ListNav.vertical(up = false, firstRow = false, target = t))
            assertEquals(ListNav.STAY, ListNav.vertical(up = false, firstRow = true, target = t)) // lista di una sola riga
        }
    }

    @Test fun suEsceSoloDallaPrimaRigaVersoIPulsantiInAlto() {
        assertEquals(ListNav.MOVE, ListNav.vertical(up = true, firstRow = false, target = ListNav.INSIDE))
        assertEquals(ListNav.MOVE, ListNav.vertical(up = true, firstRow = true, target = ListNav.OTHER))
        // mai verso le categorie, nemmeno dalla prima riga
        assertEquals(ListNav.STAY, ListNav.vertical(up = true, firstRow = true, target = ListNav.CATS))
        assertEquals(ListNav.STAY, ListNav.vertical(up = true, firstRow = true, target = ListNav.NONE))
        // a metà lista, se la riga sopra non si trova, non si salta fuori
        assertEquals(ListNav.STAY, ListNav.vertical(up = true, firstRow = false, target = ListNav.OTHER))
        assertEquals(ListNav.STAY, ListNav.vertical(up = true, firstRow = false, target = ListNav.CATS))
    }

    @Test fun laListaCambiaCategoriaSoloScorrendoLeCategorie() {
        // SU/GIÙ dentro la colonna: la lista segue il cursore, come sempre
        assertEquals(ListNav.BROWSE, ListNav.cat(byKey = true, fromInside = true, isSelected = false))
        assertEquals(ListNav.BROWSE, ListNav.cat(byKey = true, fromInside = false, isSelected = false))
        // cursore rimesso dentro la colonna dopo un aggiornamento dell'elenco: come prima
        assertEquals(ListNav.BROWSE, ListNav.cat(byKey = false, fromInside = true, isSelected = false))
        // SINISTRA dalla lista: si arriva sulla categoria in uso, niente da fare
        assertEquals(ListNav.IGNORE, ListNav.cat(byKey = false, fromInside = false, isSelected = true))
        // cursore arrivato da fuori su un'altra categoria (fine lista, pulsanti in alto): torna su quella in uso
        assertEquals(ListNav.BACK, ListNav.cat(byKey = false, fromInside = false, isSelected = false))
    }

    @Test fun elencoAggiornatoStessoTitolo() {
        val prima = List(1000) { "k$it" }
        // niente è cambiato: stessa posizione, una sola lettura
        var letture = 0
        assertEquals(500, ListNav.findNear(prima.size, 500, 200, "k500") { letture++; prima[it] })
        assertEquals(1, letture)
        // tre titoli nuovi in cima: il titolo è tre posti più giù
        val dopo = listOf("n1", "n2", "n3") + prima
        assertEquals(503, ListNav.findNear(dopo.size, 500, 200, "k500") { dopo[it] })
        // dieci titoli tolti prima: dieci posti più su
        val meno = prima.drop(10)
        assertEquals(490, ListNav.findNear(meno.size, 500, 200, "k500") { meno[it] })
        // titolo sparito, o troppo lontano: -1 (si resta alla posizione di prima)
        assertEquals(-1, ListNav.findNear(prima.size, 500, 200, "non-c-e") { prima[it] })
        assertEquals(-1, ListNav.findNear(prima.size, 100, 50, "k900") { prima[it] })
        // bordi: elenco più corto di prima, elenco vuoto, chiave vuota, righe che non si leggono
        assertEquals(4, ListNav.findNear(5, 500, 200, "k4") { prima[it] })
        assertEquals(-1, ListNav.findNear(0, 3, 200, "k3") { prima[it] })
        assertEquals(-1, ListNav.findNear(prima.size, 3, 200, "") { prima[it] })
        assertEquals(-1, ListNav.findNear(prima.size, 3, 200, "k3") { null })
    }

    @Test fun elencoAggiornatoNonLeggePiuDelNecessario() {
        // elenco piccolo: finite le righe ci si ferma, senza girare fino al limite
        val pochi = List(6) { "k$it" }
        var letture = 0
        assertEquals(-1, ListNav.findNear(pochi.size, 2, 200, "manca") { letture++; pochi[it] })
        assertEquals(6, letture)
    }
}
