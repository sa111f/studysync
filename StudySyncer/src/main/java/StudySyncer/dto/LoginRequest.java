package StudySyncer.dto;

/** Login payload — identifier can be a username or an email address. */
public class LoginRequest {

    private String identifier; // username OR email
    private String password;

    public String getIdentifier() { return identifier; }
    public String getPassword()   { return password; }

    public void setIdentifier(String identifier) { this.identifier = identifier; }
    public void setPassword(String password)     { this.password = password; }
}
