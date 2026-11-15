package com.eduaccess.bridgeaccess.controller;

import java.util.UUID;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.eduaccess.bridgeaccess.dto.SyncRequest;
import com.eduaccess.bridgeaccess.dto.SyncResponse;
import com.eduaccess.bridgeaccess.filter.DeviceIdFilter;
import com.eduaccess.bridgeaccess.service.SyncService;

/** Batch sync of the client's outbox (API doc section 9). Safe to resend as a whole. */
@RestController
public class SyncController {

	private final SyncService service;

	public SyncController(SyncService service) {
		this.service = service;
	}

	@PostMapping("/sync")
	public SyncResponse sync(@RequestAttribute(DeviceIdFilter.REQUEST_ATTRIBUTE) UUID deviceId,
			@RequestBody SyncRequest body) {
		return this.service.sync(deviceId, body);
	}

}
