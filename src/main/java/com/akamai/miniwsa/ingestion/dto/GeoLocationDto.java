package com.akamai.miniwsa.ingestion.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class GeoLocationDto {

    @JsonProperty("country")
    private String country;

    @JsonProperty("city")
    private String city;
}
