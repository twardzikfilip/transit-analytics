package com.transitanalytics.domain;

import jakarta.persistence.*;

/** A transit operator (GTFS agency.txt). */
@Entity
@Table(name = "agency")
public class Agency {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The GTFS feed this row came from ({@link TimetableFeed}); its ids are unique within it. */
    @Column(nullable = false, length = 32)
    private String feed = TimetableFeed.MAIN;

    @Column(name = "gtfs_id", nullable = false, length = 64)
    private String gtfsId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, length = 512)
    private String url;

    @Column(nullable = false, length = 64)
    private String timezone;

    @Column(length = 16)
    private String lang;

    @Column(length = 64)
    private String phone;

    @Column(name = "fare_url", length = 512)
    private String fareUrl;

    private String email;

    public String getFeed() {
        return feed;
    }

    public void setFeed(String feed) {
        this.feed = feed;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getGtfsId() {
        return gtfsId;
    }

    public void setGtfsId(String gtfsId) {
        this.gtfsId = gtfsId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getTimezone() {
        return timezone;
    }

    public void setTimezone(String timezone) {
        this.timezone = timezone;
    }

    public String getLang() {
        return lang;
    }

    public void setLang(String lang) {
        this.lang = lang;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getFareUrl() {
        return fareUrl;
    }

    public void setFareUrl(String fareUrl) {
        this.fareUrl = fareUrl;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }
}
