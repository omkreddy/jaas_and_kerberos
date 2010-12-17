import java.io.IOException;
import java.util.HashMap;
import javax.security.auth.callback.Callback;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.callback.NameCallback;
import javax.security.auth.callback.PasswordCallback;
import javax.security.auth.callback.UnsupportedCallbackException;
import javax.security.sasl.AuthorizeCallback;
import javax.security.sasl.Sasl;
import javax.security.sasl.SaslClient;
import javax.security.sasl.SaslException;
import javax.security.sasl.SaslServer;
import java.security.PrivilegedExceptionAction;
import java.security.Principal;

import java.security.PrivilegedAction;
import javax.security.auth.Subject;
import javax.security.auth.login.LoginContext;
import javax.security.auth.login.LoginException;

public class SaslizedServer {
  
  public static void main(String[] args) throws SaslException {
    new SaslizedServer().start();
  }
  
  private static class ServerCallbackHandler implements CallbackHandler {
    @Override
    public void handle(Callback[] callbacks) throws
        UnsupportedCallbackException {
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
          ac.setAuthorized(false);
        }
        if (ac.isAuthorized()) {
          ac.setAuthorizedID(authzid);
        }
      }
    }
  }
  
  private void start() throws SaslException {
    
    byte[] challenge;
    byte[] response;
    
    // Lots of diagnostics.
    System.setProperty("sun.security.krb5.debug", "true");
    System.setProperty("javax.security.sasl.level","FINEST");
    System.setProperty("handlers", "java.util.logging.ConsoleHandler");

    // Note: there are 5 variables in play here (defaults for this example given in parenthesis).
    // 1. $JAAS_CONF_FILE_NAME (jaas.conf)
    // 2. $HOST_NAME (ekoontz): The hostname that the service (this code) is running on. (might be fully qualified, or not)
    // 3. $SERVICE_NAME (testserver): The service that's running (must exist as a Kerberos principal $SERVICE_NAME/$HOSTNAME).
    // 4. $SERVICE_SECTION (SaslizedServer): The section of the JAAS configuration file $JAAS_CONF_FILE_NAME that we'll use to authenticate this service.
    // 5. $KEY_TAB_FILE_NAME (testserver.keytab): The file that holds the service's credentials.
    //
    // The file $JAAS_CONF_FILE_NAME must have :
    //
    // $SERVICE_SECTION {
    //   com.sun.security.auth.module.Krb5LoginModule required
    //   useKeyTab=true
    //   keyTab="$KEY_TAB_FILE_NAME"
    //   doNotPrompt=true
    //   useTicketCache=false
    //   storeKey=true
    //   debug=true
    //   principal="$SERVICE_NAME/$HOST_NAME";
    // };


    System.setProperty( "java.security.auth.login.config", "./jaas.conf");
    // ^^^^^^^^^^^^^^^^^$JAAS_CONF_FILE_NAME^^^^^^^^^^

    Subject subject = null;
    try {
      // Login to the KDC.
      LoginContext loginCtx = null;
      loginCtx = new LoginContext("SaslizedServer");
      // ^^^^^^^^^^^^^^$SERVICE_SECTION^^^^^^^
      loginCtx.login();
      subject = loginCtx.getSubject();

      if (subject != null) {
        try {
          SaslServer ss = Subject.doAs(subject,new PrivilegedExceptionAction<SaslServer>() {
              public SaslServer run() throws SaslException {
                System.out.println("CREATING SERVER NOW...");
                SaslServer saslServer = Sasl.createSaslServer("GSSAPI",
                                                              "testserver",
                                                              // ^^^$SERVICE_NAME^^^
                                                              "ekoontz",
                                                              // ^^^$HOST_NAME^^^
                                                              null,
                                                              // ^^^^ properties: null works fine for me.
                                                              new ServerCallbackHandler());
                System.out.println("DONE CREATING SERVER.");
                return saslServer;
              }
            });
        }
        catch (Exception e) {
          e.printStackTrace();
        }
      }
    }
    catch (LoginException e) {
      System.err.println("Login failure : " + e);
      System.exit(-1);
    }
  }
}