import javax.security.auth.callback.Callback;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.callback.UnsupportedCallbackException;
import javax.security.sasl.AuthorizeCallback;
import javax.security.sasl.Sasl;
import javax.security.sasl.SaslException;
import javax.security.sasl.SaslServer;
import java.security.PrivilegedExceptionAction;
import java.security.PrivilegedActionException;

import javax.security.auth.Subject;
import javax.security.auth.login.LoginContext;
import javax.security.auth.login.LoginException;
import java.net.ServerSocket;
import java.net.Socket;

import java.net.InetAddress;
import java.net.InetSocketAddress;

import java.io.IOException;

import java.io.DataInputStream;
import java.io.DataOutputStream;

import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.SelectorProvider;

import java.util.List;
import java.util.ArrayList;

// Nuno Santos' selector handler package.
import io.SelectorHandler;
import io.SelectorThread;


//import java.util.HashMap; // GSSContext
import java.util.Iterator;

import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

public class SASLizedServerNio {
  private ExecutorService executors = Executors.newFixedThreadPool(10);

  public static void main(String[] args) throws SaslException {
    new SASLizedServerNio().launch(Integer.parseInt(args[0]));
  }

  public void launch(final int serverPort) {

    // Lots of diagnostics.
    //    System.setProperty("sun.security.krb5.debug", "true");
    System.setProperty("javax.security.sasl.level","FINEST");
    System.setProperty("handlers", "java.util.logging.ConsoleHandler");

    // <Constants>
    final String JAAS_CONF_FILE_NAME = "jaas.conf";

    final String HOST_NAME = "ekoontz"; // The hostname that the service (this code) is running on. (might be fully qualified, or not)

    final String SERVICE_PRINCIPAL_NAME = "testserver"; // The service that we're running with this code.
                                                        // There must exist a Kerberos principal called
                                                        // $SERVICE_PRINCIPAL_NAME/$HOSTNAME.

    final String SERVICE_SECTION_OF_JAAS_CONF_FILE = "Server"; // The section (of the JAAS configuration file named $JAAS_CONF_FILE_NAME)
                                                               // that will be used to configure relevant parameters to do Kerberos authentication.

    final String KEY_TAB_FILE_NAME = "testserver.keytab"; // The file that holds the service's credentials.

    // </Constants>

    System.setProperty( "java.security.auth.login.config", JAAS_CONF_FILE_NAME);

    //
    // The file given in JAAS_CONF_FILE_NAME must have :
    //
    // $SERVICE_SECTION_OF_JAAS_CONF_FILE {
    //   com.sun.security.auth.module.Krb5LoginModule required
    //   useKeyTab=true
    //   keyTab="$KEY_TAB_FILE_NAME"
    //   doNotPrompt=true
    //   useTicketCache=false
    //   storeKey=true
    //   debug=true
    //   principal="$SERVICE_NAME/$HOST_NAME";
    // };

    try {

      final Subject subject;
      ServerSocket serverListenSocket = null;

      // 1. Login to Kerberos.
      LoginContext loginCtx = null;
      System.out.println("Authenticating using '" + SERVICE_SECTION_OF_JAAS_CONF_FILE + "' section of '" + JAAS_CONF_FILE_NAME + "'...");
      loginCtx = new LoginContext(SERVICE_SECTION_OF_JAAS_CONF_FILE);
      loginCtx.login();
      subject = loginCtx.getSubject();

      System.out.println("..authenticated.");


      // 1.5 NIO Setup

      // TODO: Convert from GSSAPI to SASL
      //      GSSContext clientContext = null;

      Selector selector = SelectorProvider.provider().openSelector();

      // Create a new non-blocking server socket channel
      ServerSocketChannel serverChannel = ServerSocketChannel.open();
      serverChannel.configureBlocking(false);
      
      // Bind the server socket to the specified address and port
      InetSocketAddress isa = new InetSocketAddress("localhost",serverPort);
      serverChannel.socket().bind(isa);
      
      // Register the server socket channel, indicating an interest in 
      // accepting new connections
      serverChannel.register(selector, SelectionKey.OP_ACCEPT);
     
      // selection key => context map.
      // TODO: Convert from GSSAPI to SASL
      // final HashMap<SelectionKey,GSSContext> clientToContext = new HashMap<SelectionKey,GSSContext>();


      int clientConnectionNumber = 0;
      // 2. Process client connections.
      while(true) {

        System.out.println("Waiting for connections from clients..");

        selector.select();
        Iterator selectedKeys = selector.selectedKeys().iterator();
        while (selectedKeys.hasNext()) {
          final SelectionKey sk = (SelectionKey) selectedKeys.next();
          selectedKeys.remove();
          
          if (!sk.isValid()) {
            System.out.println("key is not valid; continuing.");
            continue;
          }
          
          // Obtain the interest of the key
          int readyOps = sk.readyOps();
          // Disable the interest for the operation
          // that is ready. This prevents the same 
          // event from being raised multiple times.
          sk.interestOps(
                         sk.interestOps() & ~readyOps);
    
          /*          // Retrieve the handler associated with 
          // this key
          SelectorHandler handler = 
            (SelectorHandler) sk.attachment();   
          */
          // Check what event is available and deal with it according to its abilities.
          if (sk.isAcceptable()) {
            
            // FIXME: move to worker.

            System.out.println("accepting connection from client.");


            // For an accept to be pending the channel must be a server socket channel.
            ServerSocketChannel serverSocketChannel = (ServerSocketChannel) sk.channel();

            // Accept the connection and make it non-blocking
            SocketChannel clientChannel = serverSocketChannel.accept();

            clientChannel.configureBlocking(false);
            
            // Register the new SocketChannel with our Selector, indicating
            // we'd like to be notified when there's data waiting to be read
            clientChannel.register(selector, SelectionKey.OP_READ | SelectionKey.OP_WRITE);
            
            
            System.out.println("Attaching read/write handler to this key...");
            sk.attach(new ClientWorker(clientChannel,subject,SERVICE_PRINCIPAL_NAME, HOST_NAME,clientConnectionNumber++));
            System.out.println("attached.");

          } else {
            if (sk.isReadable()) {
              System.out.println("client is readable.");
            }
            if (sk.isWritable()) {
              System.out.println("client is writable.");
              final SocketChannel socketChannel = (SocketChannel) sk.channel();
            }
          }

            // 2.1. Create Sasl Server.
          //            SaslServer saslServer = createSaslServer(subject, "GSSAPI",SERVICE_PRINCIPAL_NAME,HOST_NAME);

            /*            // 2.2. Perform authentication steps until authentication process is finished.
            while (!saslServer.isComplete()) {
              exchangeTokens(saslServer,inStream,outStream);
            }
            System.out.println("Finished authenticated client: authorization id: " + saslServer.getAuthorizationID());
            
            // 2.3. Do actual useful communication with authenticated client (for now, just send order in which client connected).
            System.out.println("Writing actual message payload after authentication.");
            outStream.writeInt(clientConnectionNumber);
            */
        }
        
      }

        /*

        Socket clientConnectionSocket = null;
        clientConnectionSocket = serverListenSocket.accept();
        final DataInputStream inStream = new DataInputStream(clientConnectionSocket.getInputStream());
        final DataOutputStream outStream = new DataOutputStream(clientConnectionSocket.getOutputStream());
        System.out.println("CONNECTED.");
        System.out.println("DOING SASL AUTHENTICATION.");

        // 2.1. Create Sasl Server.
        SaslServer saslServer = createSaslServer(subject, "GSSAPI",SERVICE_PRINCIPAL_NAME,HOST_NAME);
        
        */




      //        clientConnectionNumber++;
    }
    catch (IOException e) {
      System.err.println("IOException: : " + e);
      e.printStackTrace();
      System.exit(-1);
    }
    catch (LoginException e) {
      System.err.println("LoginException: : " + e);
      e.printStackTrace();
      System.exit(-1);
    }
  }


  private class ClientWorker implements Runnable {
    private Socket clientConnectionSocket;
    private Subject serverSubject;
    private String SERVICE_PRINCIPAL_NAME;
    private String HOST_NAME;
    private int clientConnectionNumber;

    ClientWorker(SocketChannel s, Subject subj, String servicePrincipalName, String hostName, int clientConnectionNum) {

      System.out.println("ClientWorker(): initializing.");

      clientConnectionSocket = s.socket();
      serverSubject = subj;
      SERVICE_PRINCIPAL_NAME = servicePrincipalName;
      HOST_NAME = hostName;
      clientConnectionNumber = clientConnectionNum;

      System.out.println("ClientWorker(): initialized.");
    }

    public void run() {
      try {
        final DataInputStream inStream = new DataInputStream(clientConnectionSocket.getInputStream());
        final DataOutputStream outStream = new DataOutputStream(clientConnectionSocket.getOutputStream());
        System.out.println("Server: Connected.");
        System.out.println("Server: Doing SASL authentication.");
        
        SaslServer saslServer = createSaslServer(serverSubject, "GSSAPI",SERVICE_PRINCIPAL_NAME,HOST_NAME);
        
        // Perform authentication steps until authentication process is finished.
        while (!saslServer.isComplete()) {
          exchangeTokens(saslServer,inStream,outStream);
        }
        
        System.out.println("Server: Successfully authenticated client with authorization id: " + saslServer.getAuthorizationID());
        System.out.println("Server: Writing actual message payload after authentication.");
        outStream.writeInt(clientConnectionNumber);
        System.out.println("Server: Finished writing to client.");
      }
      catch (Exception e) {
        System.err.println("ClientWorker Exception: " + e);
        e.printStackTrace();
      }
      finally {
        try {
          clientConnectionSocket.close();
        }
        catch (Exception e) {
          System.err.println("ClientWorker Exception closing client connection socket: " + e);
          e.printStackTrace();
        }
      }
    }
  }

  private static SaslServer createSaslServer(final Subject subject, final String mech,final String principalName,final String hostName) {
    try {
      return Subject.doAs(subject,new PrivilegedExceptionAction<SaslServer>() {
          public SaslServer run() {
            SaslServer saslServer = null;
            try {
              System.out.println("creating SaslServer with service subject..");
              saslServer = Sasl.createSaslServer(mech,principalName,hostName,null,new ServerCallbackHandler());
              System.out.println("..done.");
            }
            catch (SaslException e) {
              System.err.println("Error creating SaslServer.");
              e.printStackTrace();
            }
            return saslServer;
          }
        }
        );
    }
    catch (PrivilegedActionException e) {
      System.err.println("Error creating SaslServer object while calling doAs((principal='" + principalName + "'),..)");
      e.printStackTrace();
    }
    return null;
  }

  private static void exchangeTokens(SaslServer saslServer, DataInputStream inStream, DataOutputStream outStream) throws SaslException {
    try {
      int length = inStream.readInt();

      System.out.println("Server: read integer: " + length);
      byte[] saslToken = new byte[length];
      inStream.readFully(saslToken,0,length);
      System.out.println("Server: response token read of length " + saslToken.length);
      try {
        saslToken = saslServer.evaluateResponse(saslToken);
        if (saslToken != null) {
          if (saslToken.length > 0) {
            outStream.writeInt(saslToken.length);
            outStream.write(saslToken,0,saslToken.length);
            outStream.flush();
            System.out.println("Wrote token of length: " + saslToken.length);
          }
          else {
            outStream.writeInt(0);
            System.out.println("Challenge length is 0: not sending (just sending integer 0 length).");
          }
        }
        else {
          System.out.println("evaluateResponse() returned a null token: continuing without writing anything to client.");
        }
      }
      catch (SaslException e) {
        System.err.println("exchangeTokens(): throwing SaslException.");
        throw e;
      }
    }
    catch (IOException e) {
      System.err.println("Failed to read integer from client.");
      e.printStackTrace();
    }
  }
  
  private static class ServerCallbackHandler implements CallbackHandler {
    @Override
    public void handle(Callback[] callbacks) throws
        UnsupportedCallbackException {
      System.out.println("ServerCallbackHandler::handle()");
      AuthorizeCallback ac = null;
      for (Callback callback : callbacks) {
        if (callback instanceof AuthorizeCallback) {
          ac = (AuthorizeCallback) callback;
        } else {
          throw new UnsupportedCallbackException(callback,
              "Unrecognized SASL GSSAPI Callback");
        }
      }
      if (ac != null) {
        String authid = ac.getAuthenticationID();
        String authzid = ac.getAuthorizationID();

        if (authid.equals(authzid)) {
          ac.setAuthorized(true);
        } else {
          if (true) {
            System.out.println("authid != authzid; setting to authorized anyway.");
            ac.setAuthorized(true);
          }
          else {
            ac.setAuthorized(false);
          }
        }
        if (ac.isAuthorized()) {
          ac.setAuthorizedID(authzid);
        }
      }
    }
  }

}