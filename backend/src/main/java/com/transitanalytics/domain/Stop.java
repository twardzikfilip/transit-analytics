package com.transitanalytics.domain;

import jakarta.persistence.*;

/** A stop or station (GTFS stops.txt). */
@Entity
@Table(name = "stop")
public class Stop {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The GTFS feed this row came from ({@link TimetableFeed}); its ids are unique within it. */
    @Column(nullable = false, length = 32)
    private String feed = TimetableFeed.MAIN;

    @Column(name = "gtfs_id", nullable = false, length = 64)
    private String gtfsId;

    @Column(length = 32)
    private String code;

    @Column(nullable = false)
    private String name;

    @Column(columnDefinition = "text")
    private String description;

    private Double latitude;

    private Double longitude;

    @Column(name = "zone_id", length = 64)
    private String zoneId;

    @Column(length = 512)
    private String url;

    @Column(name = "location_type")
    private Short locationType;

    /** gtfs_id of the parent station (same feed), if any. */
    @Column(name = "parent_station", length = 64)
    private String parentStation;

    @Column(name = "wheelchair_boarding")
    private Short wheelchairBoarding;

    @Column(name = "platform_code", length = 32)
    private String platformCode;

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

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Double getLatitude() {
        return latitude;
    }

    public void setLatitude(Double latitude) {
        this.latitude = latitude;
    }

    public Double getLongitude() {
        return longitude;
    }

    public void setLongitude(Double longitude) {
        this.longitude = longitude;
    }

    public String getZoneId() {
        return zoneId;
    }

    public void setZoneId(String zoneId) {
        this.zoneId = zoneId;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public Short getLocationType() {
        return locationType;
    }

    public void setLocationType(Short locationType) {
        this.locationType = locationType;
    }

    public String getParentStation() {
        return parentStation;
    }

    public void setParentStation(String parentStation) {
        this.parentStation = parentStation;
    }

    public Short getWheelchairBoarding() {
        return wheelchairBoarding;
    }

    public void setWheelchairBoarding(Short wheelchairBoarding) {
        this.wheelchairBoarding = wheelchairBoarding;
    }

    public String getPlatformCode() {
        return platformCode;
    }

    public void setPlatformCode(String platformCode) {
        this.platformCode = platformCode;
    }
}
