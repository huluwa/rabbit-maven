package rabbit.handler;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.ByteBuffer;
import rabbit.http.HttpHeader;
import rabbit.httpio.BlockListener;
import rabbit.httpio.FileResourceSource;
import rabbit.httpio.ResourceSource;
import rabbit.io.BufferHandle;
import rabbit.io.FileHelper;
import rabbit.nio.DefaultTaskIdentifier;
import rabbit.nio.TaskIdentifier;
import rabbit.proxy.Connection;
import rabbit.proxy.HttpProxy;
import rabbit.proxy.TrafficLoggerHandler;
import rabbit.util.SProperties;

/** This handler first downloads the image runs convert on it and 
 *  then serves the smaller image.
 * @author <a href="mailto:robo@khelekore.org">Robert Olofsson</a>
 */
public class ImageHandler extends BaseHandler {
    private static final String STD_CONVERT = "/usr/bin/convert";
    private static final String STD_CONVERT_ARGS = 
    "-quality 10 -flatten $filename +profile \"*\" jpeg:$filename.c";
    private SProperties config = new SProperties ();
    private boolean doConvert = true;

    private boolean converted = false;
    private long lowQualitySize = -1;
    private File convertedFile = null;
    private File typeFile = null;
    private int minSizeToConvert = 2000;

    /** For creating the factory.
     */
    public ImageHandler () {	
    }

    /** Create a new ImageHandler for the given request.
     * @param con the Connection handling the request.
     * @param request the actual request made.
     * @param clientHandle the client side buffer.
     * @param response the actual response.
     * @param content the resource.
     * @param mayCache May we cache this request? 
     * @param mayFilter May we filter this request?
     * @param size the size of the data beeing handled.
     */
    public ImageHandler (Connection con, TrafficLoggerHandler tlh, 
			 HttpHeader request, BufferHandle clientHandle,
			 HttpHeader response, ResourceSource content, 
			 boolean mayCache, boolean mayFilter, long size, 
			 SProperties config, boolean doConvert, 
			 int minSizeToConvert) {
	super (con, tlh, request, clientHandle, response, content, 
	       mayCache, mayFilter, size);
	if (size == -1)
	    con.setKeepalive (false);
	con.setChunking (false);
	this.config = config;
	this.doConvert = doConvert;
	this.minSizeToConvert = minSizeToConvert;
    }

    @Override 
    public Handler getNewInstance (Connection con, TrafficLoggerHandler tlh, 
				   HttpHeader header, BufferHandle bufHandle, 
				   HttpHeader webHeader, 
				   ResourceSource content, boolean mayCache, 
				   boolean mayFilter, long size) {
	return new ImageHandler (con, tlh, header, bufHandle, webHeader, 
				 content, mayCache, mayFilter, size, 
				 config, doConvert, minSizeToConvert);
    }

    /** 
     * ®return true this handler modifies the content.
     */
    @Override public boolean changesContentSize () {
	return true;
    }

    /** Images needs to be cacheable to be compressed.
     * @return true
     */
    @Override protected boolean mayCacheFromSize () {
	return true;
    }

    /** Check if this handler may force the cached resource to be less than the cache max size.
     * @return false
     */
    @Override protected boolean mayRestrictCacheSize () {
	return false;
    }
    
    /** Try to convert the image before letting the superclass handle it.
     */
    @Override public void handle () {
	try {
	    tryconvert ();
	} catch (IOException e) {
	    failed (e);
	}
    }

    @Override protected void addCache () {
	if (!converted)
	    super.addCache ();
	// if we get here then we have converted the image
	// and do not want a cache... 
    }

    /** clear up the mess we made (remove intermediate files etc).
     */
    @Override protected void finish (boolean good) {
	try {
	    if (convertedFile != null)
		deleteFile (convertedFile);
	} finally {
	    super.finish (good);
	}
    }

    /** Remove the cachestream and the cache entry.
     */
    @Override protected void removeCache () {
	super.removeCache ();
	if (convertedFile != null)
	    deleteFile (convertedFile);
    }


    /** Try to convert the image. This is done like this:
     *  <xmp>
     *  super.addCache ();
     *  readImage();
     *  convertImage();
     *  cacheChannel = null;
     *  </xmp>
     *  We have to use the cachefile to convert the image, and if we
     *  convert it we dont want to write the file to the cache later
     *  on. 
     */
    protected void tryconvert () throws IOException {
	// TODO: if the image size is unknown (chunked) we will have -1 > 2000 
	// TODO: perhaps we should add something to handle that.
	if (doConvert && mayFilter && mayCache && size > minSizeToConvert) {
	    super.addCache ();
	    // check if cache setup worked.
	    if (cacheChannel == null) 
		super.handle ();
	    else
		readImage ();
	} else {
	    super.handle ();
	}
    } 

    /** Read in the image
     * @throws IOException if reading of the image fails.
     */
    protected void readImage () throws IOException {
	content.addBlockListener (new ImageReader ());	
    }

    private class ImageReader implements BlockListener {
	public void bufferRead (final BufferHandle bufHandle) {
	    TaskIdentifier ti = 
		new DefaultTaskIdentifier (getClass ().getSimpleName (), 
					   request.getRequestURI ());
	    con.getNioHandler ().runThreadTask (new Runnable () {
		    public void run () {
			writeImageData (bufHandle);
		    }
		}, ti);
	}
	
	private void writeImageData (BufferHandle bufHandle) {
	    try {
		ByteBuffer buf = bufHandle.getBuffer ();
		writeCache (buf);
		totalRead += buf.remaining ();
		buf.position (buf.limit ());
		bufHandle.possiblyFlush ();
		content.addBlockListener (this);
	    } catch (IOException e) {
		failed (e);
	    }
	}
	
	public void finishedRead () {
	    try {
		if (size > 0 && totalRead != size)
		    setPartialContent (totalRead, size);		
		cacheChannel.close ();
		cacheChannel = null;
		convertImage ();
	    } catch (IOException e) {
		failed (e);
	    }
	}
	
	public void failed (Exception cause) {
	    ImageHandler.this.failed (cause);
	}
	
	public void timeout () {
	    ImageHandler.this.failed (new IOException ("Timeout"));
	}
    }

    private void closeStreams (Process ps) throws IOException {
	ps.getInputStream ().close ();
	ps.getOutputStream ().close ();
	ps.getErrorStream ().close ();
    }

    /** Convert the image into a small low quality image (normally a jpeg).
     * @throws IOException if conversion fails.
     */
    protected void convertImage () {
	TaskIdentifier ti = 
	    new DefaultTaskIdentifier (getClass ().getSimpleName () +
				       ".convertImage", 
				       request.getRequestURI ());
				      
	con.getNioHandler ().runThreadTask (new Runnable () {
		public void run () {
		    try {
			internalConvertImage ();
			converted = true;
			ImageHandler.super.handle ();
		    } catch (IOException e) {
			failed (e);
		    }
		}
	    }, ti);
    }
    
    protected void internalConvertImage () throws IOException {
	long origSize = size;
	String convert = config.getProperty ("convert", STD_CONVERT);
	String convargs = config.getProperty ("convertargs", STD_CONVERT_ARGS);
	
	int idx = 0;
	HttpProxy proxy = con.getProxy ();
	String entryName = 
	    proxy.getCache ().getEntryName (entry.getId (), false, null);
	try {
	    while ((idx = convargs.indexOf ("$filename")) > -1) {
		convargs = convargs.substring (0, idx) + entryName + 
		    convargs.substring (idx + "$filename".length());
	    }
	    String command = convert + " " + convargs;	    
	    getLogger ().fine ("ImageHandler running: '" + command + "'");
	    Process ps = Runtime.getRuntime ().exec (command);
	    try {
		ps.waitFor ();
		closeStreams (ps);
		int exitValue = ps.exitValue (); 
		if (exitValue != 0) {
		    getLogger ().warning ("Bad conversion: " + entryName + 
					  ", got exit value: " + exitValue);
		    throw new IOException ("failed to convert image, " + 
					   "exit value: " + exitValue);
		} 
	    } catch (InterruptedException e) {
		getLogger ().warning ("Interupted during wait for: " + 
				      entryName);
	    }
	    
	    convertedFile = new File (entryName + ".c");
	    typeFile = new File (entryName + ".type");
	    lowQualitySize = convertedFile.length ();
	    if (lowQualitySize > 0 && origSize > lowQualitySize) {
		String ctype = checkFileType (typeFile);		
		response.setHeader ("Content-Type", ctype);
		/** We need to remove the existing file first for 
		 *  windows system, they will not overwrite files in a move.
		 *  Spotted by: Michael Mlivoncic 
		 */
		File oldEntry = new File (entryName);
		if (oldEntry.exists ())
		    FileHelper.delete (oldEntry);
		if (convertedFile.renameTo (new File (entryName)))
		    convertedFile = null;
		else 
		    getLogger ().warning ("rename failed: " + 
					  convertedFile.getName () + 
					  " => " + 
					  entryName);
	    }
	} finally { 
	    if (convertedFile != null)
		deleteFile (convertedFile);
	    if (typeFile != null && typeFile.exists ())
		deleteFile (typeFile);
	}
	size = (lowQualitySize < origSize ? lowQualitySize : origSize);
	response.setHeader ("Content-length", "" + size);
	con.setExtraInfo ("imageratio:" + lowQualitySize + "/" + origSize + 
			  "=" + ((float)lowQualitySize / origSize));	
	content.release ();
	content = new FileResourceSource (entryName, con.getNioHandler (),
					  con.getBufferHandler ());
	convertedFile = null;
    }

    private String checkFileType (File typeFile) throws IOException {
	String ctype = "image/jpeg";
	if (typeFile.exists () && typeFile.length() > 0) {
	    BufferedReader br = null; 
	    try {
		br = new BufferedReader (new FileReader (typeFile));
		ctype = br.readLine();
	    } finally {
		if (br != null)
		    br.close ();
	    }
	}
	return ctype;
    }
    
    @Override public void setup (SProperties prop) {
	super.setup (prop);
	if (prop != null) {	    
	    config = prop;
	    doConvert = true;
	
	    String conv = prop.getProperty ("convert", STD_CONVERT);
	    File f = new File (conv);
	    if (!f.exists () || !f.isFile()) {
		getLogger ().warning ("convert -" + conv + 
				      "- not found, is your path correct?");
		doConvert = false;
	    }
	    
	    minSizeToConvert = 
		Integer.parseInt (prop.getProperty ("min_size", "2000"));
	}
    }
}
