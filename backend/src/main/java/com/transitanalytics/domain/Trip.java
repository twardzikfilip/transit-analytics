package com.transitanalytics.domain;

import jakarta.persistence.*;

/** One run of a route on a service day pattern (GTFS trips.txt). */
@Entity
@Table(name = "trip")
public class Trip {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The GTFS feed this row came from ({@link TimetableFeed}); its ids are unique within it. */
    @Column(nullable = false, length = 32)
    private String feed = TimetableFeed.MAIN;

    @Column(name = "gtfs_id", nullable = false, length = 64)
    private String gtfsId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "route_id", nullable = false)
    private Route route;


    /** Matches calendar_date.service_id of the same feed. */
    @Column(name = "service_id", nullable = false, length = 64)
    private String serviceId;

    private String headsign;

    @Column(name = "short_name", length = 64)
    private String shortName;

    /** GTFS direction_id: 0 or 1. */
    private Short direction;

    @Column(name = "block_id", length = 64)
    private String blockId;

    /** Matches shape_point.shape_id of the same feed. */
    @Column(name = "shape_id", length = 64)
    private String shapeId;

    @Column(name = "wheelchair_accessible")
    private Short wheelchairAccessible;

    @Column(name = "bikes_allowed")
    private Short bikesAllowed;

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

    public Route getRoute() {
        return route;
    }

    public void setRoute(Route route) {
        this.route = route;
    }


    public String getServiceId() {
        return serviceId;
    }

    public void setServiceId(String serviceId) {
        this.serviceId = serviceId;
    }

    public String getHeadsign() {
        return headsign;
    }

    public void setHeadsign(String headsign) {
        this.headsign = headsign;
    }

    public String getShortName() {
        return shortName;
    }

    public void setShortName(String shortName) {
        this.shortName = shortName;
    }

    public Short getDirection() {
        return direction;
    }

    public void setDirection(Short direction) {
        this.direction = direction;
    }

    public String getBlockId() {
        return blockId;
    }

    public void setBlockId(String blockId) {
        this.blockId = blockId;
    }

    public String getShapeId() {
        return shapeId;
    }

    public void setShapeId(String shapeId) {
        this.shapeId = shapeId;
    }

    public Short getWheelchairAccessible() {
        return wheelchairAccessible;
    }

    public void setWheelchairAccessible(Short wheelchairAccessible) {
        this.wheelchairAccessible = wheelchairAccessible;
    }

    public Short getBikesAllowed() {
        return bikesAllowed;
    }

    public void setBikesAllowed(Short bikesAllowed) {
        this.bikesAllowed = bikesAllowed;
    }
}
