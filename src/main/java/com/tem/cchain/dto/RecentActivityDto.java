package com.tem.cchain.dto;

import java.time.LocalDateTime;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RecentActivityDto {
	private String documentTitle;
	private LocalDateTime verrifiedAt;
	private String rewardAmount;

}
