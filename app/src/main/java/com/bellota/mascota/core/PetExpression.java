package com.bellota.mascota.core;

/** Expresiones disponibles para la mascota. */
public enum PetExpression {
    NEUTRAL("neutral", "Normal"),
    HAPPY("happy", "Feliz"),
    THINKING("thinking", "Pensando"),
    SURPRISED("surprised", "Sorpresa"),
    SLEEPY("sleepy", "Sueño"),
    LOVING("loving", "Cariño"),
    SAD("sad", "Triste"),
    ANGRY("angry", "Enojada");

    public final String asset;
    public final String label;

    PetExpression(String asset, String label) {
        this.asset = asset;
        this.label = label;
    }
}
