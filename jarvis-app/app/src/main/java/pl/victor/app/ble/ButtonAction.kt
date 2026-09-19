package pl.victor.app.ble

/**
 * Akcje użytkownika wykryte przez analizę przycisku.
 */
sealed class ButtonAction {
    object QUICK_QUESTION : ButtonAction()
    object LOOK_AND_DESCRIBE : ButtonAction()

    /**
     * Zdjęcie, odczytanie napisu i PRZETŁUMACZENIE go na język odpowiedzi.
     *
     * Nazywało się to READ_TEXT i było mylące aż do szkody: obok istnieje
     * [pl.victor.app.actions.Action.ReadText], które robi coś INNEGO - włącza
     * ciągły tryb czytania dla osoby niewidomej, kawałek po kawałku na
     * żądanie. Ta akcja jest jednorazowa i tłumaczy: powstała z prośby
     * "żeby po przytrzymaniu od razu czytał po polsku to, co widzi - taki
     * szybki tłumacz", do nazw produktów w sklepie i tabliczek.
     *
     * Dwie nazwy różniące się wielkością liter, dla dwóch różnych funkcji,
     * kosztowały już jedną złą diagnozę.
     */
    object READ_AND_TRANSLATE : ButtonAction()

    object SCAN_QR : ButtonAction()
    object NEW_CONVERSATION : ButtonAction()
}
