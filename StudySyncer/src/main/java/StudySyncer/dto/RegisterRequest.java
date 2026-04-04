package StudySyncer.dto;

/** Sign-up payload for LOCAL (email/password) registration. */
public class RegisterRequest {

    private String username;
    private String email;
    private String password;
    private String confirmPassword;

    public String getUsername()        { return username; }
    public String getEmail()           { return email; }
    public String getPassword()        { return password; }
    public String getConfirmPassword() { return confirmPassword; }

    public void setUsername(String username)               { this.username = username; }
    public void setEmail(String email)                     { this.email = email; }
    public void setPassword(String password)               { this.password = password; }
    public void setConfirmPassword(String confirmPassword) { this.confirmPassword = confirmPassword; }
}
