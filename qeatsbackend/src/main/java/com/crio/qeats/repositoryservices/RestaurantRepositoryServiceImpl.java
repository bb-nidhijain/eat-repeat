/*
 *
 *  * Copyright (c) Crio.Do 2019. All rights reserved
 *
 */

package com.crio.qeats.repositoryservices;

import ch.hsr.geohash.GeoHash;
import redis.clients.jedis.Jedis;
import com.crio.qeats.dto.Restaurant;
import com.crio.qeats.configs.RedisConfiguration;
import com.crio.qeats.globals.GlobalConstants;
import com.crio.qeats.models.RestaurantEntity;
import com.crio.qeats.repositories.RestaurantRepository;
import com.crio.qeats.utils.GeoLocation;
import com.crio.qeats.utils.GeoUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.inject.Provider;
import org.modelmapper.ModelMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.scheduling.annotation.AsyncResult;
import org.springframework.stereotype.Service;

@Primary
@Service
public class RestaurantRepositoryServiceImpl implements RestaurantRepositoryService {

  @Autowired
  private RedisConfiguration redisConfiguration;

  @Autowired
  private RestaurantRepository restaurantRepository;
  
  @Autowired
  private MongoTemplate mongoTemplate;

  @Autowired
  private Provider<ModelMapper> modelMapperProvider;

  private boolean isOpenNow(LocalTime time, RestaurantEntity res) {
    DateTimeFormatter formatter = DateTimeFormatter.ISO_LOCAL_TIME;
    LocalTime openingTime = LocalTime.parse(res.getOpensAt(),formatter);
    LocalTime closingTime = LocalTime.parse(res.getClosesAt(),formatter);

    return time.isAfter(openingTime) && time.isBefore(closingTime);
  }

  // TODO: CRIO_TASK_MODULE_NOSQL
  // Objectives:
  // 1. Implement findAllRestaurantsCloseby.
  // 2. Remember to keep the precision of GeoHash in mind while using it as a key.
  // Check RestaurantRepositoryService.java file for the interface contract.
  public List<Restaurant> findAllRestaurantsCloseBy(Double latitude,
      Double longitude, LocalTime currentTime, Double servingRadiusInKms) {

        
    if (redisConfiguration.isCacheAvailable()) {
      return findAllRestaurantsCache(latitude, longitude, currentTime, servingRadiusInKms);
    }
      
    else {
      return findAllRestaurantsMongo(latitude, longitude, currentTime, servingRadiusInKms);
    }
    
  }
  public List<Restaurant> findAllRestaurantsMongo(Double latitude, Double longitude,
      LocalTime currentTime, Double servingRadiusInKms) {
    List<RestaurantEntity> res = restaurantRepository.findAll();

    List<Restaurant> restaurants = new ArrayList<>();
    ObjectMapper objectMapper = new ObjectMapper();
    for (RestaurantEntity restro : res) {
      if (isRestaurantCloseByAndOpen(restro, currentTime, latitude, longitude,
          servingRadiusInKms)) {
        Restaurant restaurant = modelMapperProvider.get().map(restro, Restaurant.class);
        // Restaurant restaurant = modelMapperProvider.get().map(restro, Restaurant.class);
        restaurants.add(restaurant);
      }
    }
    String restaurantDbString = "";
    redisConfiguration.initCache();
    try {
      restaurantDbString = objectMapper.writeValueAsString(restaurants);
    } catch (IOException e) {
      e.printStackTrace();
    }
    System.out.print(restaurantDbString);

    GeoLocation geoLocation = new GeoLocation(latitude, longitude);
    GeoHash geoHash =
        GeoHash.withCharacterPrecision(geoLocation.getLatitude(), geoLocation.getLongitude(), 7);
    Jedis jedis = redisConfiguration.getJedisPool().getResource();
    jedis.set(geoHash.toBase32(), restaurantDbString);
    return restaurants;
  }

  public List<Restaurant> findAllRestaurantsCache(Double latitude, Double longitude,
      LocalTime currentTime, Double servingRadiusInKms) {

    List<Restaurant> restaurantList = new ArrayList<>();

    GeoLocation geoLocation = new GeoLocation(latitude, longitude);
    GeoHash geoHash =
        GeoHash.withCharacterPrecision(geoLocation.getLatitude(), geoLocation.getLongitude(), 7);

    Jedis jedis = redisConfiguration.getJedisPool().getResource();;
    try {
      // jedis = redisConfiguration.getJedisPool().getResource();
      String jsonStringFromCache = jedis.get(geoHash.toBase32());

      if (jsonStringFromCache == null) {
        // Cache needs to be updated.
        String createdJsonString = "";
        try {
          restaurantList = findAllRestaurantsMongo(geoLocation.getLatitude(),
              geoLocation.getLongitude(), currentTime, servingRadiusInKms);
          createdJsonString = new ObjectMapper().writeValueAsString(restaurantList);
        } catch (JsonProcessingException e) {
          e.printStackTrace();
        }

        // Do operations with jedis resource
        jedis.setex(geoHash.toBase32(), GlobalConstants.REDIS_ENTRY_EXPIRY_IN_SECONDS,
            createdJsonString);
      } else {
        try {
          restaurantList = new ObjectMapper().readValue(jsonStringFromCache,
              new TypeReference<List<Restaurant>>() {});
        } catch (IOException e) {
          e.printStackTrace();
        }
      }
    } finally {
      if (jedis != null) {
        jedis.close();
      }
    }
    return restaurantList;
  }
  





  // TODO: CRIO_TASK_MODULE_NOSQL
  // Objective:
  // 1. Check if a restaurant is nearby and open. If so, it is a candidate to be returned.
  // NOTE: How far exactly is "nearby"?

  /**
   * Utility method to check if a restaurant is within the serving radius at a given time.
   * @return boolean True if restaurant falls within serving radius and is open, false otherwise
   */
  private boolean isRestaurantCloseByAndOpen(RestaurantEntity restaurantEntity,
      LocalTime currentTime, Double latitude, Double longitude, Double servingRadiusInKms) {
    if (isOpenNow(currentTime, restaurantEntity)) {
      GeoUtils geoUtils = new GeoUtils();
      Double d = geoUtils.findDistanceInKm(latitude, longitude,
          restaurantEntity.getLatitude(), restaurantEntity.getLongitude());
      // System.out.println(d);
      return d < servingRadiusInKms;
    }
    return false;
  }



}

