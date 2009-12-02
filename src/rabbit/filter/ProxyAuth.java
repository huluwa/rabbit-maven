package rabbit.filter;

import java.net.MalformedURLException;
import java.net.URL;
import java.nio.channels.SocketChannel;
import java.util.logging.Level;
import java.util.logging.Logger;
import rabbit.filter.authenticate.Authenticator;
import rabbit.filter.authenticate.PlainFileAuthenticator;
import rabbit.filter.authenticate.SQLAuthenticator;
import rabbit.http.HttpHeader;
import rabbit.proxy.Connection;
import rabbit.proxy.HttpGenerator;
import rabbit.util.SProperties;

/** This is a filter that requires users to use proxy-authentication.
 *
 * @author <a href="mailto:robo@khelekore.org">Robert Olofsson</a>
 */
public class ProxyAuth implements HttpFilter {
    private final Logger logger = Logger.getLogger (getClass ().getName ());
    private Authenticator authenticator;
    
    /** test if a socket/header combination is valid or return a new HttpHeader.
     *  Check that the user has been authenticate..
     * @param socket the SocketChannel that made the request.
     * @param header the actual request made.
     * @param con the Connection handling the request.
     * @return null if everything is fine or a HttpHeader 
     *         describing the error (like a 403).
     */
    public HttpHeader doHttpInFiltering (SocketChannel socket, 
					 HttpHeader header, Connection con) {
	if (con.getMeta ())
	    return null;
	String username = con.getUserName ();
	String pwd = con.getPassword ();
	if (username == null || pwd == null) 
	    return getError (con, header);
	if (!authenticator.authenticate (username, pwd, con.getChannel ()))
	    return getError (con, header);
	return null;
    }

    private HttpHeader getError (Connection con, HttpHeader header) {
	HttpGenerator hg = con.getHttpGenerator ();
	try {
	    return hg.get407 ("internet", new URL (header.getRequestURI ()));
	} catch (MalformedURLException e) {
	    logger.log (Level.WARNING, "Bad url: " + header.getRequestURI (), e);
	    return hg.get407 ("internet", null);
	}
    }

    /** test if a socket/header combination is valid or return a new HttpHeader.
     *  does nothing.
     * @param socket the SocketChannel that made the request.
     * @param header the actual request made.
     * @param con the Connection handling the request.
     * @return This method always returns null.
     */
    public HttpHeader doHttpOutFiltering (SocketChannel socket, 
					  HttpHeader header, Connection con) {
	return null;
    }    

    /** Setup this class with the given properties.
     * @param properties the new configuration of this class.
     */
    public void setup (SProperties properties) {
	String authType = properties.getProperty ("authenticator", "plain");
	if ("plain".equalsIgnoreCase (authType)) 
	    authenticator = new PlainFileAuthenticator (properties);
	else if ("sql".equalsIgnoreCase (authType))
	    authenticator = new SQLAuthenticator (properties);
    }
}
