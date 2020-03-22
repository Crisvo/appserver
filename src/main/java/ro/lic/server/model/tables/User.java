package ro.lic.server.model.tables;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;
import org.springframework.stereotype.Component;
import ro.lic.server.model.Roles;

import javax.persistence.*;
import java.io.Serializable;
import java.sql.Time;
import  java.util.Date;

/***/
@Entity
@Table(name = "Users")
public class User implements Serializable {

    //region Constructors
    protected User(){

    }

    public User(Roles role, String username, String password, String name, String address, String phoneNumber, Date createdOn, String programStart, String programEnd) {
        this.role = role;
        this.username = username;
        this.password = password;
        this.name = name;
        this.address = address;
        this.phoneNumber = phoneNumber;
        this.createdOn = createdOn;
        this.programStart = programStart;
        this.programEnd = programEnd;
        isOnline = false;
    }
    //endregion

    //region Table columns
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Long id;

    @Column (name = "Role",
             nullable = false)
    private Roles role;

    @Column(nullable = false,
            unique = true)
    private String username;
    @Column(nullable = false,
            unique = true)
    private String password;

    @Column(length = 100)
    private String name;
    @Column(length = 350)
    private String address;
    @Column(length = 10)
    private String phoneNumber;

    @Column(nullable = false)
    @SerializedName("creationDate")
    private Date createdOn;

    @Column(nullable = false,
            length = 5)
    private String programStart;
    @Column(nullable = false,
            length = 5)
    private String programEnd;

    @Column(nullable = false, columnDefinition = "boolean default false")
    private transient boolean isOnline;

    public Roles getRole() {
        return role;
    }
    //endregion

    public static User fromJson(String json){
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        return gson.fromJson(json, User.class);
    }

    //region Getters and Setters
    public void setRole(Roles role) {
        this.role = role;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public String getPhoneNumber() {
        return phoneNumber;
    }

    public void setPhoneNumber(String phoneNumber) {
        this.phoneNumber = phoneNumber;
    }

    public Date getCreatedOn() {
        return createdOn;
    }

    public void setCreatedOn(Date createdOn) {
        this.createdOn = createdOn;
    }

    public String getProgramStart() {
        return programStart;
    }

    public void setProgramStart(String programStart) {
        this.programStart = programStart;
    }

    public String getProgramEnd() {
        return programEnd;
    }

    public void setProgramEnd(String programEnd) {
        this.programEnd = programEnd;
    }
    //endregion
}
