package com.transitanalytics.domain;

import jakarta.persistence.*;

/** A transit line (GTFS routes.txt). */
@Entity
@Table(name = "route")
public class Route {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "gtfs_id", nullable = false, unique = true, length = 64)
    private String gtfsId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "agency_id")
    private Agency agency;

    @Column(name = "short_name", length = 32)
    private String shortName;

    @Column(name = "long_name")
    private String longName;

    @Column(columnDefinition = "text")
    private String description;

    /** GTFS route type code, e.g. 3 = bus. */
    @Column(name = "route_type", nullable = false)
    private Short routeType;

    @Column(length = 512)
    private String url;

    /** "#RRGGBB". */
    @Column(length = 7)
    private String color;

    @Column(name = "text_color", length = 7)
    private String textColor;

    @Column(name = "sort_order")
    private Integer sortOrder;

    /** Urban, suburban, night or regional line. */
    @Column(nullable = false, length = 16)
    private RouteSubtype subtype = RouteSubtype.URBAN;

    /** How the line runs relative to the city centre; null until classified (BKM lines only). */
    @Column(length = 16)
    private RouteGeometry geometry;

    /** How much the line carries; null until classified (BKM lines only). */
    @Column(name = "capacity_class", length = 16)
    private RouteCapacity capacityClass;

    /** The company operating the line for the organiser: KPKM, KPK or KZK (classification/operators.yml). */
    @Column(name = "operating_agency", length = 16)
    private String operatingAgency;

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

    public Agency getAgency() {
        return agency;
    }

    public void setAgency(Agency agency) {
        this.agency = agency;
    }

    public String getShortName() {
        return shortName;
    }

    public void setShortName(String shortName) {
        this.shortName = shortName;
    }

    public String getLongName() {
        return longName;
    }

    public void setLongName(String longName) {
        this.longName = longName;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Short getRouteType() {
        return routeType;
    }

    public void setRouteType(Short routeType) {
        this.routeType = routeType;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getColor() {
        return color;
    }

    public void setColor(String color) {
        this.color = color;
    }

    public String getTextColor() {
        return textColor;
    }

    public void setTextColor(String textColor) {
        this.textColor = textColor;
    }

    public Integer getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(Integer sortOrder) {
        this.sortOrder = sortOrder;
    }

    public RouteSubtype getSubtype() {
        return subtype;
    }

    public void setSubtype(RouteSubtype subtype) {
        this.subtype = subtype;
    }

    public RouteGeometry getGeometry() {
        return geometry;
    }

    public void setGeometry(RouteGeometry geometry) {
        this.geometry = geometry;
    }

    public RouteCapacity getCapacityClass() {
        return capacityClass;
    }

    public void setCapacityClass(RouteCapacity capacityClass) {
        this.capacityClass = capacityClass;
    }

    public String getOperatingAgency() {
        return operatingAgency;
    }

    public void setOperatingAgency(String operatingAgency) {
        this.operatingAgency = operatingAgency;
    }

    /** What riders see: the short name, or the long name when there is none. */
    public String displayName() {
        return shortName != null && !shortName.isBlank() ? shortName : longName;
    }
}
