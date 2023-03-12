package com.crio.qeats.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

@AllArgsConstructor
@Getter @Setter
public class RestauratQuery {

  private Double latitude;
  private Double longitude;

  private String searchOption;




  
}
