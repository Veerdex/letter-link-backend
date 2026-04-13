package com.backend.letterlink;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/matchmaking")
public class MatchmakingController {

    private static final String HEADER_PLAYER_ID = "X-Player-Id";
    private static final String HEADER_PLAYER_TOKEN = "X-Player-Token";

    private final MatchmakingService matchmakingService;

    public MatchmakingController(MatchmakingService matchmakingService) {
        this.matchmakingService = matchmakingService;
    }

    @PostMapping("/queue")
    public ResponseEntity<ApiResponse<ApiModels.QueueTicketData>> queue(
        @RequestBody(required = false) ApiModels.QueueForMatchRequest request,
        @RequestHeader(value = HEADER_PLAYER_ID, required = false) String authPlayerId,
        @RequestHeader(value = HEADER_PLAYER_TOKEN, required = false) String authToken
    ) {
        return matchmakingService.queueForMatch(request, authPlayerId, authToken);
    }

    @PostMapping("/cancel")
    public ResponseEntity<ApiResponse<ApiModels.CancelQueueData>> cancel(
        @RequestBody(required = false) ApiModels.CancelQueueRequest request,
        @RequestHeader(value = HEADER_PLAYER_ID, required = false) String authPlayerId,
        @RequestHeader(value = HEADER_PLAYER_TOKEN, required = false) String authToken
    ) {
        return matchmakingService.cancelQueue(request, authPlayerId, authToken);
    }

    @PostMapping("/heartbeat")
    public ResponseEntity<ApiResponse<ApiModels.QueueHeartbeatData>> heartbeat(
        @RequestBody(required = false) ApiModels.QueueHeartbeatRequest request,
        @RequestHeader(value = HEADER_PLAYER_ID, required = false) String authPlayerId,
        @RequestHeader(value = HEADER_PLAYER_TOKEN, required = false) String authToken
    ) {
        return matchmakingService.heartbeat(request, authPlayerId, authToken);
    }

    @GetMapping("/status")
    public ResponseEntity<ApiResponse<ApiModels.MatchStatusData>> status(
        @RequestParam(required = false) String ticketId,
        @RequestHeader(value = HEADER_PLAYER_ID, required = false) String authPlayerId,
        @RequestHeader(value = HEADER_PLAYER_TOKEN, required = false) String authToken
    ) {
        return matchmakingService.getStatus(ticketId, authPlayerId, authToken);
    }

    @PostMapping("/acknowledge")
    public ResponseEntity<ApiResponse<ApiModels.AcknowledgeMatchData>> acknowledge(
        @RequestBody(required = false) ApiModels.AcknowledgeMatchRequest request,
        @RequestHeader(value = HEADER_PLAYER_ID, required = false) String authPlayerId,
        @RequestHeader(value = HEADER_PLAYER_TOKEN, required = false) String authToken
    ) {
        return matchmakingService.acknowledgeMatch(request, authPlayerId, authToken);
    }

    @PostMapping("/abandon")
    public ResponseEntity<ApiResponse<ApiModels.AbandonMatchData>> abandon(
        @RequestBody(required = false) ApiModels.AbandonMatchRequest request,
        @RequestHeader(value = HEADER_PLAYER_ID, required = false) String authPlayerId,
        @RequestHeader(value = HEADER_PLAYER_TOKEN, required = false) String authToken
    ) {
        return matchmakingService.abandonMatch(request, authPlayerId, authToken);
    }
}
