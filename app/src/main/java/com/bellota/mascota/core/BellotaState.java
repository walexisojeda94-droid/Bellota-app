package com.bellota.mascota.core;

/** Estado compartido de la mascota. La conexión se mantiene separada de la expresión. */
public class BellotaState {
    public enum Connection { LOCAL, CONNECTING, ONLINE, RECOVERING }

    private PetExpression expression = PetExpression.HAPPY;
    private Connection connection = Connection.LOCAL;

    public PetExpression getExpression() { return expression; }
    public void setExpression(PetExpression value) { expression = value; }
    public Connection getConnection() { return connection; }
    public void setConnection(Connection value) { connection = value; }
}
