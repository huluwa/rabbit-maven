package rabbit.rnio;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.concurrent.Executors;

import rabbit.rnio.impl.AcceptingServer;
import rabbit.rnio.impl.AcceptorListener;
import rabbit.rnio.impl.CachingBufferHandler;
import rabbit.rnio.impl.SimpleBlockSender;
import rabbit.rnio.impl.SimpleBlockReader;

/** An echo server built using rnio. This echo server will handle
 *  many concurrent clients without any problems.
 *
 * @author <a href="mailto:robo@khelekore.org">Robert Olofsson</a>
 */
public class EchoServer {
    private final AcceptingServer as;
    private final BufferHandler bufferHandler;
    private final AcceptListener acceptHandler;

    private final ByteBuffer QUIT =
	ByteBuffer.wrap ("quit\r\n".getBytes ("UTF-8"));

    /** Start a new EchoServer
     * @param args the command line arguments
     */
    public static void main (String[] args) {
	int port = 9999;

	if (args.length > 0)
	    port = Integer.parseInt (args[0]);

	try {
	    EchoServer es = new EchoServer (port);
	    es.start ();
	} catch (IOException e) {
	    e.printStackTrace ();
	}
    }

    /** Create a new EchoServer listening on the given port
     * @param port the port lnumber to listen on
     * @throws IOException if creating the server fails
     */
    public EchoServer (int port) throws IOException {
	bufferHandler = new CachingBufferHandler ();
	acceptHandler = new AcceptListener ();
	as = new AcceptingServer (null, port, acceptHandler,
				  Executors.newCachedThreadPool (), 1,
				  Long.valueOf (15000));
    }

    /** Start listening for connections
     */
    public void start () {
	as.start ();
    }

    private void quit () {
	as.shutdown ();
    }

    private Long getTimeout () {
	long now = System.currentTimeMillis ();
	return now + 60 * 1000;
    }

    private class AcceptListener implements AcceptorListener {
	public void connectionAccepted (SocketChannel sc) throws IOException {
	    Reader rh = new Reader (sc, as.getNioHandler (), getTimeout ());
	    rh.register ();
	}
    }

    private class Reader extends SimpleBlockReader {
	public Reader (SocketChannel sc, NioHandler nioHandler, Long timeout) {
	    super (sc, nioHandler, timeout);
	}

	/** Use the direct byte buffers from the bufferHandler */
	@Override public ByteBuffer getByteBuffer () {
	    return bufferHandler.getBuffer ();
	}

	/** Cache the ByteBuffer again */
	@Override public void putByteBuffer (ByteBuffer buf) {
	    bufferHandler.putBuffer (buf);
	}

	@Override public void channelClosed () {
	    closed ();
	}

	@Override public void handleBufferRead (ByteBuffer buf) {
	    if (quitMessage (buf)) {
		quit ();
	    } else {
		Writer writer = 
		    new Writer (sc, nioHandler, buf, this, getTimeout ());
		writer.write ();
	    }
	}

	private boolean quitMessage (ByteBuffer buf) {
	    return buf.compareTo (QUIT) == 0;
	}
    }

    private class Writer extends SimpleBlockSender {
	private Reader reader;

	public Writer (SocketChannel sc, NioHandler nioHandler,
		       ByteBuffer buf, Reader reader, Long timeout) {
	    super (sc, nioHandler, buf, timeout);
	    this.reader = reader;
	}

	@Override public void done () {
	    bufferHandler.putBuffer (getBuffer ());
	    reader.register ();
	}

	@Override public void closed () {
	    bufferHandler.putBuffer (getBuffer ());
	    super.closed ();
	}
    }
}