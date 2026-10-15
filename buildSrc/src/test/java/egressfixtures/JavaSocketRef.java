package egressfixtures;

import java.net.Socket;
import java.util.function.Supplier;

/**
 * A constructor reference: Socket is named only as a bootstrap argument of the invokedynamic (the method's own signature
 * says Object). Nothing here runs.
 */
public class JavaSocketRef {
    public Supplier<Object> sockets() {
        return Socket::new;
    }
}
