package com.backend.letterlink;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class MatchmakingService {

    private static final Object MUTATION_LOCK = new Object();
    private static final String HEADER_PLAYER_ID = "X-Player-Id";
    private static final String HEADER_PLAYER_TOKEN = "X-Player-Token";
    private static final AtomicInteger ERROR_COUNTER = new AtomicInteger(3000);

    public ResponseEntity<ApiResponse<ApiModels.QueueTicketData>> queueForMatch(
        ApiModels.QueueForMatchRequest request,
        String authPlayerId,
        String authToken
    ) {
        if (request == null) {
            return badRequest("Request body is required");
        }

        String idError = validatePlayerId(request.id);
        if (idError != null) {
            return badRequest(idError);
        }

        if (!request.id.equals(authPlayerId)) {
            return unauthorized("Player id does not match authenticated session");
        }

        String modeError = validateQueueMode(request.mode);
        if (modeError != null) {
            return badRequest(modeError);
        }

        String gamemodeError = validateGamemode(request.currentGamemode);
        if (gamemodeError != null) {
            return badRequest(gamemodeError);
        }

        String boardError = validateBoardSize(request.boardWidth, request.boardHeight);
        if (boardError != null) {
            return badRequest(boardError);
        }

        synchronized (MUTATION_LOCK) {
            try (Connection conn = Database.getConnection()) {
                Database.ensureSchema(conn);

                if (!hasValidSession(conn, authPlayerId, authToken)) {
                    return unauthorized("Invalid player credentials");
                }

                if (fetchOpenMatchForPlayer(conn, request.id) != null) {
                    return conflict("Player already has an active match");
                }

                QueueTicketRecord existing = fetchActiveTicketByPlayer(conn, request.id);
                if (existing != null) {
                    return ok(toQueueTicketData(existing));
                }

                PlayerRecord player = fetchPlayer(conn, request.id);
                if (player == null) {
                    return notFound("Player not found");
                }

                String queueMode = request.mode.trim().toLowerCase(Locale.ROOT);
                int power = request.power == null ? GameDefaults.DEFAULT_MATCH_POWER : Math.max(0, request.power.intValue());
                int mmr = fetchMmr(conn, request.id, GameDefaults.boardMode(request.boardWidth, request.boardHeight));
                String now = Instant.now().toString();
                String ticketId = UUID.randomUUID().toString();

                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("""
                        INSERT INTO matchmaking_queue (
                            ticket_id,
                            player_id,
                            username,
                            mode,
                            current_gamemode,
                            board_width,
                            board_height,
                            mmr,
                            power,
                            status,
                            match_id,
                            queued_at,
                            last_seen_at,
                            created_at,
                            updated_at
                        )
                        VALUES (
                            '%s',
                            '%s',
                            '%s',
                            '%s',
                            '%s',
                            %d,
                            %d,
                            %d,
                            %d,
                            '%s',
                            NULL,
                            '%s',
                            '%s',
                            '%s',
                            '%s'
                        )
                    """.formatted(
                        escapeSql(ticketId),
                        escapeSql(request.id),
                        escapeSql(player.username),
                        escapeSql(queueMode),
                        escapeSql(request.currentGamemode),
                        request.boardWidth,
                        request.boardHeight,
                        mmr,
                        power,
                        GameDefaults.QUEUE_STATUS_QUEUED,
                        escapeSql(now),
                        escapeSql(now),
                        escapeSql(now),
                        escapeSql(now)
                    ));
                }

                QueueTicketRecord ticket = fetchTicketById(conn, ticketId);
                return ok(toQueueTicketData(ticket));

            } catch (Exception e) {
                return serverError("Error queueing for match", e);
            }
        }
    }

    public ResponseEntity<ApiResponse<ApiModels.CancelQueueData>> cancelQueue(
        ApiModels.CancelQueueRequest request,
        String authPlayerId,
        String authToken
    ) {
        synchronized (MUTATION_LOCK) {
            try (Connection conn = Database.getConnection()) {
                Database.ensureSchema(conn);

                if (!hasValidSession(conn, authPlayerId, authToken)) {
                    return unauthorized("Invalid player credentials");
                }

                QueueTicketRecord ticket = resolveOwnedTicket(conn, authPlayerId, request == null ? null : request.ticketId);
                if (ticket == null) {
                    return notFound("Queue ticket not found");
                }

                String now = Instant.now().toString();
                ApiModels.CancelQueueData data = new ApiModels.CancelQueueData();
                data.ticketId = ticket.ticketId;
                data.updatedAt = now;

                MatchRecord match = isBlank(ticket.matchId) ? null : fetchMatchById(conn, ticket.matchId);
                if (GameDefaults.QUEUE_STATUS_QUEUED.equals(ticket.status)) {
                    try (Statement stmt = conn.createStatement()) {
                        stmt.executeUpdate("""
                            UPDATE matchmaking_queue
                            SET status = '%s', updated_at = '%s'
                            WHERE ticket_id = '%s'
                        """.formatted(
                            GameDefaults.QUEUE_STATUS_CANCELLED,
                            escapeSql(now),
                            escapeSql(ticket.ticketId)
                        ));
                    }
                    data.cancelled = true;
                    data.ticketStatus = GameDefaults.QUEUE_STATUS_CANCELLED;
                    return ok(data);
                }

                data.cancelled = false;
                data.ticketStatus = ticket.status;
                data.matchId = ticket.matchId;
                data.matchStatus = match == null ? null : match.status;
                return ok(data);

            } catch (Exception e) {
                return serverError("Error cancelling queue", e);
            }
        }
    }

    public ResponseEntity<ApiResponse<ApiModels.QueueHeartbeatData>> heartbeat(
        ApiModels.QueueHeartbeatRequest request,
        String authPlayerId,
        String authToken
    ) {
        synchronized (MUTATION_LOCK) {
            try (Connection conn = Database.getConnection()) {
                Database.ensureSchema(conn);

                if (!hasValidSession(conn, authPlayerId, authToken)) {
                    return unauthorized("Invalid player credentials");
                }

                QueueTicketRecord ticket = resolveOwnedTicket(conn, authPlayerId, request == null ? null : request.ticketId);
                if (ticket == null) {
                    return notFound("Queue ticket not found");
                }

                String now = Instant.now().toString();
                if (GameDefaults.QUEUE_STATUS_QUEUED.equals(ticket.status) || GameDefaults.QUEUE_STATUS_MATCHED.equals(ticket.status)) {
                    try (Statement stmt = conn.createStatement()) {
                        stmt.executeUpdate("""
                            UPDATE matchmaking_queue
                            SET last_seen_at = '%s', updated_at = '%s'
                            WHERE ticket_id = '%s'
                        """.formatted(
                            escapeSql(now),
                            escapeSql(now),
                            escapeSql(ticket.ticketId)
                        ));
                    }
                    ticket = fetchTicketById(conn, ticket.ticketId);
                }

                MatchRecord match = isBlank(ticket.matchId) ? null : fetchMatchById(conn, ticket.matchId);
                ApiModels.QueueHeartbeatData data = new ApiModels.QueueHeartbeatData();
                data.ticketId = ticket.ticketId;
                data.ticketStatus = ticket.status;
                data.matchId = ticket.matchId;
                data.matchStatus = match == null ? null : match.status;
                data.updatedAt = now;
                return ok(data);

            } catch (Exception e) {
                return serverError("Error recording matchmaking heartbeat", e);
            }
        }
    }

    public ResponseEntity<ApiResponse<ApiModels.MatchStatusData>> getStatus(
        String ticketId,
        String authPlayerId,
        String authToken
    ) {
        try (Connection conn = Database.getConnection()) {
            Database.ensureSchema(conn);

            if (!hasValidSession(conn, authPlayerId, authToken)) {
                return unauthorized("Invalid player credentials");
            }

            QueueTicketRecord ticket = resolveOwnedTicket(conn, authPlayerId, ticketId);
            if (ticket == null) {
                return notFound("Queue ticket not found");
            }

            return ok(toMatchStatusData(conn, ticket, authPlayerId));

        } catch (Exception e) {
            return serverError("Error getting matchmaking status", e);
        }
    }

    public ResponseEntity<ApiResponse<ApiModels.AcknowledgeMatchData>> acknowledgeMatch(
        ApiModels.AcknowledgeMatchRequest request,
        String authPlayerId,
        String authToken
    ) {
        if (request == null || isBlank(request.matchId)) {
            return badRequest("matchId is required");
        }

        synchronized (MUTATION_LOCK) {
            try (Connection conn = Database.getConnection()) {
                Database.ensureSchema(conn);

                if (!hasValidSession(conn, authPlayerId, authToken)) {
                    return unauthorized("Invalid player credentials");
                }

                MatchRecord match = fetchMatchById(conn, request.matchId);
                if (match == null) {
                    return notFound("Match not found");
                }

                if (!match.hasPlayer(authPlayerId)) {
                    return unauthorized("Player is not part of this match");
                }

                if (!(GameDefaults.MATCH_STATUS_MATCH_FOUND.equals(match.status)
                    || GameDefaults.MATCH_STATUS_READY.equals(match.status)
                    || GameDefaults.MATCH_STATUS_IN_PROGRESS.equals(match.status))) {
                    return conflict("Match can no longer be acknowledged");
                }

                String now = Instant.now().toString();
                if (GameDefaults.MATCH_STATUS_MATCH_FOUND.equals(match.status)) {
                    try (Statement stmt = conn.createStatement()) {
                        if (authPlayerId.equals(match.player1Id)) {
                            stmt.executeUpdate("""
                                UPDATE matches
                                SET player1_acknowledged = 1, updated_at = '%s'
                                WHERE id = '%s'
                            """.formatted(escapeSql(now), escapeSql(match.id)));
                        } else {
                            stmt.executeUpdate("""
                                UPDATE matches
                                SET player2_acknowledged = 1, updated_at = '%s'
                                WHERE id = '%s'
                            """.formatted(escapeSql(now), escapeSql(match.id)));
                        }
                    }
                }

                match = fetchMatchById(conn, request.matchId);
                if (match != null && match.player1Acknowledged && match.player2Acknowledged && isBlank(match.boardLetters)
                    && GameDefaults.MATCH_STATUS_MATCH_FOUND.equals(match.status)) {
                    String boardLetters = MatchBoardGenerator.generateBoard(match.power, match.boardWidth, match.boardHeight);
                    try (Statement stmt = conn.createStatement()) {
                        stmt.executeUpdate("""
                            UPDATE matches
                            SET board_letters = '%s', status = '%s', ready_at = '%s', updated_at = '%s'
                            WHERE id = '%s'
                        """.formatted(
                            escapeSql(boardLetters),
                            GameDefaults.MATCH_STATUS_READY,
                            escapeSql(now),
                            escapeSql(now),
                            escapeSql(match.id)
                        ));
                    }
                    match = fetchMatchById(conn, request.matchId);
                }

                ApiModels.AcknowledgeMatchData data = new ApiModels.AcknowledgeMatchData();
                data.matchId = request.matchId;
                data.matchStatus = match == null ? null : match.status;
                data.playerAcknowledged = match != null && match.isAcknowledged(authPlayerId);
                data.bothAcknowledged = match != null && match.player1Acknowledged && match.player2Acknowledged;
                data.ready = match != null && GameDefaults.MATCH_STATUS_READY.equals(match.status);
                data.power = match == null ? GameDefaults.DEFAULT_MATCH_POWER : match.power;
                data.boardLetters = match == null ? null : match.boardLetters;
                data.boardRows = buildBoardRows(match == null ? null : match.boardLetters, match == null ? 0 : match.boardWidth, match == null ? 0 : match.boardHeight);
                data.updatedAt = now;
                return ok(data);

            } catch (Exception e) {
                return serverError("Error acknowledging match", e);
            }
        }
    }

    public ResponseEntity<ApiResponse<ApiModels.AbandonMatchData>> abandonMatch(
        ApiModels.AbandonMatchRequest request,
        String authPlayerId,
        String authToken
    ) {
        if (request == null || isBlank(request.matchId)) {
            return badRequest("matchId is required");
        }

        synchronized (MUTATION_LOCK) {
            try (Connection conn = Database.getConnection()) {
                Database.ensureSchema(conn);

                if (!hasValidSession(conn, authPlayerId, authToken)) {
                    return unauthorized("Invalid player credentials");
                }

                MatchRecord match = fetchMatchById(conn, request.matchId);
                if (match == null) {
                    return notFound("Match not found");
                }

                if (!match.hasPlayer(authPlayerId)) {
                    return unauthorized("Player is not part of this match");
                }

                if (GameDefaults.MATCH_STATUS_FINISHED.equals(match.status)
                    || GameDefaults.MATCH_STATUS_ABANDONED.equals(match.status)
                    || GameDefaults.MATCH_STATUS_CANCELLED.equals(match.status)) {
                    return conflict("Match can no longer be abandoned");
                }

                String now = Instant.now().toString();
                applyAbandonment(conn, match, authPlayerId, now);
                PlayerRecord player = fetchPlayer(conn, authPlayerId);

                ApiModels.AbandonMatchData data = new ApiModels.AbandonMatchData();
                data.matchId = match.id;
                data.matchStatus = GameDefaults.MATCH_STATUS_ABANDONED;
                data.banAmount = player == null ? 0 : player.banAmount;
                data.updatedAt = now;
                return ok(data);

            } catch (Exception e) {
                return serverError("Error abandoning match", e);
            }
        }
    }

    @Scheduled(fixedDelay = 1000)
    public void runMatchmaker() {
        synchronized (MUTATION_LOCK) {
            try (Connection conn = Database.getConnection()) {
                Database.ensureSchema(conn);
                expireStaleQueueTickets(conn);
                handleMatchFoundTimeouts(conn);
                handleActiveMatchDisconnects(conn);
                createMatches(conn);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private void expireStaleQueueTickets(Connection conn) throws Exception {
        Instant staleBefore = Instant.now().minusSeconds(GameDefaults.QUEUE_STALE_AFTER_SECONDS);
        try (Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("""
                UPDATE matchmaking_queue
                SET status = '%s', updated_at = '%s'
                WHERE status = '%s' AND last_seen_at < '%s'
            """.formatted(
                GameDefaults.QUEUE_STATUS_EXPIRED,
                escapeSql(Instant.now().toString()),
                GameDefaults.QUEUE_STATUS_QUEUED,
                escapeSql(staleBefore.toString())
            ));
        }
    }

    private void handleMatchFoundTimeouts(Connection conn) throws Exception {
        List<MatchRecord> pendingMatches = fetchMatchesByStatus(conn, GameDefaults.MATCH_STATUS_MATCH_FOUND);
        Instant now = Instant.now();
        Instant staleBefore = now.minusSeconds(GameDefaults.QUEUE_STALE_AFTER_SECONDS);

        for (MatchRecord match : pendingMatches) {
            boolean timedOut = Duration.between(Instant.parse(match.createdAt), now).getSeconds() >= GameDefaults.MATCH_ACK_TIMEOUT_SECONDS;
            QueueTicketRecord ticket1 = fetchTicketByMatchAndPlayer(conn, match.id, match.player1Id);
            QueueTicketRecord ticket2 = fetchTicketByMatchAndPlayer(conn, match.id, match.player2Id);

            boolean ticket1Alive = ticket1 != null && GameDefaults.QUEUE_STATUS_MATCHED.equals(ticket1.status)
                && Instant.parse(ticket1.lastSeenAt).compareTo(staleBefore) >= 0;
            boolean ticket2Alive = ticket2 != null && GameDefaults.QUEUE_STATUS_MATCHED.equals(ticket2.status)
                && Instant.parse(ticket2.lastSeenAt).compareTo(staleBefore) >= 0;

            if (!timedOut && ticket1Alive && ticket2Alive) {
                continue;
            }

            String nowText = now.toString();
            if (ticket1 != null) {
                updateTicketAfterMatchFoundFailure(conn, ticket1, ticket1Alive, nowText);
            }
            if (ticket2 != null) {
                updateTicketAfterMatchFoundFailure(conn, ticket2, ticket2Alive, nowText);
            }

            try (Statement stmt = conn.createStatement()) {
                stmt.executeUpdate("""
                    UPDATE matches
                    SET status = '%s', finished_at = '%s', updated_at = '%s'
                    WHERE id = '%s'
                """.formatted(
                    GameDefaults.MATCH_STATUS_CANCELLED,
                    escapeSql(nowText),
                    escapeSql(nowText),
                    escapeSql(match.id)
                ));
            }
        }
    }

    private void handleActiveMatchDisconnects(Connection conn) throws Exception {
        List<MatchRecord> activeMatches = new ArrayList<MatchRecord>();
        activeMatches.addAll(fetchMatchesByStatus(conn, GameDefaults.MATCH_STATUS_READY));
        activeMatches.addAll(fetchMatchesByStatus(conn, GameDefaults.MATCH_STATUS_IN_PROGRESS));

        Instant staleBefore = Instant.now().minusSeconds(GameDefaults.QUEUE_STALE_AFTER_SECONDS);
        String now = Instant.now().toString();

        for (MatchRecord match : activeMatches) {
            QueueTicketRecord ticket1 = fetchTicketByMatchAndPlayer(conn, match.id, match.player1Id);
            QueueTicketRecord ticket2 = fetchTicketByMatchAndPlayer(conn, match.id, match.player2Id);

            boolean ticket1Alive = ticket1 != null && Instant.parse(ticket1.lastSeenAt).compareTo(staleBefore) >= 0;
            boolean ticket2Alive = ticket2 != null && Instant.parse(ticket2.lastSeenAt).compareTo(staleBefore) >= 0;

            if (ticket1Alive && ticket2Alive) {
                continue;
            }

            String leaverId = !ticket1Alive ? match.player1Id : match.player2Id;
            applyAbandonment(conn, match, leaverId, now);
        }
    }

    private void createMatches(Connection conn) throws Exception {
        List<QueueTicketRecord> queued = fetchQueuedTickets(conn);
        Set<String> used = new HashSet<String>();
        Instant now = Instant.now();

        for (int i = 0; i < queued.size(); i++) {
            QueueTicketRecord a = queued.get(i);
            if (used.contains(a.ticketId)) {
                continue;
            }

            for (int j = i + 1; j < queued.size(); j++) {
                QueueTicketRecord b = queued.get(j);
                if (used.contains(b.ticketId)) {
                    continue;
                }
                if (!sameQueueBucket(a, b)) {
                    continue;
                }
                if (!withinRange(a, b, now)) {
                    continue;
                }

                createMatch(conn, a, b, now.toString());
                used.add(a.ticketId);
                used.add(b.ticketId);
                break;
            }
        }
    }

    private void createMatch(Connection conn, QueueTicketRecord a, QueueTicketRecord b, String now) throws Exception {
        String matchId = UUID.randomUUID().toString();
        int power = Math.max(a.power, b.power);

        try (Statement stmt = conn.createStatement()) {
            stmt.execute("""
                INSERT INTO matches (
                    id,
                    player1_id,
                    player2_id,
                    player1_username,
                    player2_username,
                    mode,
                    current_gamemode,
                    board_width,
                    board_height,
                    power,
                    status,
                    player1_acknowledged,
                    player2_acknowledged,
                    board_letters,
                    ready_at,
                    started_at,
                    finished_at,
                    abandoned_by_player_id,
                    created_at,
                    updated_at
                )
                VALUES (
                    '%s',
                    '%s',
                    '%s',
                    '%s',
                    '%s',
                    '%s',
                    '%s',
                    %d,
                    %d,
                    %d,
                    '%s',
                    0,
                    0,
                    NULL,
                    NULL,
                    NULL,
                    NULL,
                    NULL,
                    '%s',
                    '%s'
                )
            """.formatted(
                escapeSql(matchId),
                escapeSql(a.playerId),
                escapeSql(b.playerId),
                escapeSql(a.username),
                escapeSql(b.username),
                escapeSql(a.mode),
                escapeSql(a.currentGamemode),
                a.boardWidth,
                a.boardHeight,
                power,
                GameDefaults.MATCH_STATUS_MATCH_FOUND,
                escapeSql(now),
                escapeSql(now)
            ));

            stmt.executeUpdate("""
                UPDATE matchmaking_queue
                SET status = '%s', match_id = '%s', updated_at = '%s'
                WHERE ticket_id IN ('%s', '%s')
            """.formatted(
                GameDefaults.QUEUE_STATUS_MATCHED,
                escapeSql(matchId),
                escapeSql(now),
                escapeSql(a.ticketId),
                escapeSql(b.ticketId)
            ));
        }
    }

    private void updateTicketAfterMatchFoundFailure(Connection conn, QueueTicketRecord ticket, boolean requeue, String now) throws Exception {
        try (Statement stmt = conn.createStatement()) {
            if (requeue) {
                stmt.executeUpdate("""
                    UPDATE matchmaking_queue
                    SET status = '%s', match_id = NULL, queued_at = '%s', updated_at = '%s'
                    WHERE ticket_id = '%s'
                """.formatted(
                    GameDefaults.QUEUE_STATUS_QUEUED,
                    escapeSql(now),
                    escapeSql(now),
                    escapeSql(ticket.ticketId)
                ));
            } else {
                stmt.executeUpdate("""
                    UPDATE matchmaking_queue
                    SET status = '%s', match_id = NULL, updated_at = '%s'
                    WHERE ticket_id = '%s'
                """.formatted(
                    GameDefaults.QUEUE_STATUS_EXPIRED,
                    escapeSql(now),
                    escapeSql(ticket.ticketId)
                ));
            }
        }
    }

    private void applyAbandonment(Connection conn, MatchRecord match, String leaverId, String now) throws Exception {
        if (GameDefaults.MATCH_STATUS_ABANDONED.equals(match.status)) {
            return;
        }

        try (Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("""
                UPDATE matches
                SET status = '%s', abandoned_by_player_id = '%s', finished_at = '%s', updated_at = '%s'
                WHERE id = '%s'
            """.formatted(
                GameDefaults.MATCH_STATUS_ABANDONED,
                escapeSql(leaverId),
                escapeSql(now),
                escapeSql(now),
                escapeSql(match.id)
            ));

            stmt.executeUpdate("""
                UPDATE players
                SET ban_amount = ban_amount + 1, updated_at = '%s'
                WHERE id = '%s'
            """.formatted(
                escapeSql(now),
                escapeSql(leaverId)
            ));

            stmt.executeUpdate("""
                UPDATE matchmaking_queue
                SET status = '%s', updated_at = '%s'
                WHERE match_id = '%s' AND status = '%s'
            """.formatted(
                GameDefaults.QUEUE_STATUS_COMPLETED,
                escapeSql(now),
                escapeSql(match.id),
                GameDefaults.QUEUE_STATUS_MATCHED
            ));
        }
    }

    private boolean sameQueueBucket(QueueTicketRecord a, QueueTicketRecord b) {
        return a.mode.equals(b.mode)
            && a.currentGamemode.equals(b.currentGamemode)
            && a.boardWidth == b.boardWidth
            && a.boardHeight == b.boardHeight;
    }

    private boolean withinRange(QueueTicketRecord a, QueueTicketRecord b, Instant now) {
        long secondsA = Duration.between(Instant.parse(a.queuedAt), now).getSeconds();
        long secondsB = Duration.between(Instant.parse(b.queuedAt), now).getSeconds();
        int rangeA = GameDefaults.matchmakingRange(a.mode, secondsA);
        int rangeB = GameDefaults.matchmakingRange(b.mode, secondsB);
        int diff = Math.abs(a.mmr - b.mmr);
        return diff <= rangeA && diff <= rangeB;
    }

    private QueueTicketRecord resolveOwnedTicket(Connection conn, String authPlayerId, String requestedTicketId) throws Exception {
        QueueTicketRecord ticket = !isBlank(requestedTicketId) ? fetchTicketById(conn, requestedTicketId) : fetchLatestTicketByPlayer(conn, authPlayerId);
        if (ticket == null) {
            return null;
        }
        if (!authPlayerId.equals(ticket.playerId)) {
            return null;
        }
        return ticket;
    }

    private ApiModels.QueueTicketData toQueueTicketData(QueueTicketRecord ticket) {
        ApiModels.QueueTicketData data = new ApiModels.QueueTicketData();
        data.ticketId = ticket.ticketId;
        data.ticketStatus = ticket.status;
        data.mode = ticket.mode;
        data.currentGamemode = ticket.currentGamemode;
        data.boardWidth = ticket.boardWidth;
        data.boardHeight = ticket.boardHeight;
        data.mmr = ticket.mmr;
        data.power = ticket.power;
        data.queuedAt = ticket.queuedAt;
        data.updatedAt = ticket.updatedAt;
        data.matchId = ticket.matchId;
        return data;
    }

    private ApiModels.MatchStatusData toMatchStatusData(Connection conn, QueueTicketRecord ticket, String authPlayerId) throws Exception {
        ApiModels.MatchStatusData data = new ApiModels.MatchStatusData();
        data.ticketId = ticket.ticketId;
        data.ticketStatus = ticket.status;
        data.matchId = ticket.matchId;
        data.updatedAt = ticket.updatedAt;

        if (!isBlank(ticket.matchId)) {
            MatchRecord match = fetchMatchById(conn, ticket.matchId);
            if (match != null) {
                data.matchStatus = match.status;
                data.playerAcknowledged = match.isAcknowledged(authPlayerId);
                data.bothAcknowledged = match.player1Acknowledged && match.player2Acknowledged;
                data.ready = GameDefaults.MATCH_STATUS_READY.equals(match.status) || GameDefaults.MATCH_STATUS_IN_PROGRESS.equals(match.status);
                data.mode = match.mode;
                data.currentGamemode = match.currentGamemode;
                data.boardWidth = match.boardWidth;
                data.boardHeight = match.boardHeight;
                data.power = match.power;
                data.boardLetters = match.boardLetters;
                data.boardRows = buildBoardRows(match.boardLetters, match.boardWidth, match.boardHeight);
                data.updatedAt = match.updatedAt;
                if (authPlayerId.equals(match.player1Id)) {
                    data.opponentId = match.player2Id;
                    data.opponentUsername = match.player2Username;
                } else {
                    data.opponentId = match.player1Id;
                    data.opponentUsername = match.player1Username;
                }
            }
        }

        return data;
    }

    private List<String> buildBoardRows(String boardLetters, int width, int height) {
        List<String> rows = new ArrayList<String>();
        if (boardLetters == null || width <= 0 || height <= 0) {
            return rows;
        }
        for (int row = 0; row < height; row++) {
            int start = row * width;
            int end = Math.min(start + width, boardLetters.length());
            rows.add(boardLetters.substring(start, end));
        }
        return rows;
    }

    private PlayerRecord fetchPlayer(Connection conn, String playerId) throws Exception {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("""
                 SELECT id, username, ban_amount, updated_at
                 FROM players
                 WHERE id = '%s'
             """.formatted(escapeSql(playerId)))) {
            if (!rs.next()) {
                return null;
            }
            PlayerRecord player = new PlayerRecord();
            player.id = rs.getString("id");
            player.username = rs.getString("username");
            player.banAmount = rs.getInt("ban_amount");
            player.updatedAt = rs.getString("updated_at");
            return player;
        }
    }

    private int fetchMmr(Connection conn, String playerId, String boardMode) throws Exception {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("""
                 SELECT mmr
                 FROM player_mmr
                 WHERE player_id = '%s' AND mode = '%s'
             """.formatted(escapeSql(playerId), escapeSql(boardMode)))) {
            if (rs.next()) {
                return rs.getInt("mmr");
            }
        }
        return GameDefaults.DEFAULT_MMR;
    }

    private QueueTicketRecord fetchActiveTicketByPlayer(Connection conn, String playerId) throws Exception {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("""
                 SELECT *
                 FROM matchmaking_queue
                 WHERE player_id = '%s' AND status IN ('%s', '%s')
                 ORDER BY created_at DESC
             """.formatted(
                 escapeSql(playerId),
                 GameDefaults.QUEUE_STATUS_QUEUED,
                 GameDefaults.QUEUE_STATUS_MATCHED
             ))) {
            if (rs.next()) {
                return mapQueueTicket(rs);
            }
        }
        return null;
    }

    private QueueTicketRecord fetchLatestTicketByPlayer(Connection conn, String playerId) throws Exception {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("""
                 SELECT *
                 FROM matchmaking_queue
                 WHERE player_id = '%s'
                 ORDER BY created_at DESC
             """.formatted(escapeSql(playerId)))) {
            if (rs.next()) {
                return mapQueueTicket(rs);
            }
        }
        return null;
    }

    private QueueTicketRecord fetchTicketById(Connection conn, String ticketId) throws Exception {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("""
                 SELECT *
                 FROM matchmaking_queue
                 WHERE ticket_id = '%s'
             """.formatted(escapeSql(ticketId)))) {
            if (rs.next()) {
                return mapQueueTicket(rs);
            }
        }
        return null;
    }

    private QueueTicketRecord fetchTicketByMatchAndPlayer(Connection conn, String matchId, String playerId) throws Exception {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("""
                 SELECT *
                 FROM matchmaking_queue
                 WHERE match_id = '%s' AND player_id = '%s'
                 ORDER BY updated_at DESC
             """.formatted(escapeSql(matchId), escapeSql(playerId)))) {
            if (rs.next()) {
                return mapQueueTicket(rs);
            }
        }
        return null;
    }

    private List<QueueTicketRecord> fetchQueuedTickets(Connection conn) throws Exception {
        List<QueueTicketRecord> tickets = new ArrayList<QueueTicketRecord>();
        Instant staleBefore = Instant.now().minusSeconds(GameDefaults.QUEUE_STALE_AFTER_SECONDS);
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("""
                 SELECT *
                 FROM matchmaking_queue
                 WHERE status = '%s' AND last_seen_at >= '%s'
                 ORDER BY mode, current_gamemode, board_width, board_height, queued_at, mmr
             """.formatted(
                 GameDefaults.QUEUE_STATUS_QUEUED,
                 escapeSql(staleBefore.toString())
             ))) {
            while (rs.next()) {
                tickets.add(mapQueueTicket(rs));
            }
        }
        return tickets;
    }

    private MatchRecord fetchOpenMatchForPlayer(Connection conn, String playerId) throws Exception {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("""
                 SELECT *
                 FROM matches
                 WHERE (player1_id = '%s' OR player2_id = '%s')
                   AND status IN ('%s', '%s', '%s')
                 ORDER BY created_at DESC
             """.formatted(
                 escapeSql(playerId),
                 escapeSql(playerId),
                 GameDefaults.MATCH_STATUS_MATCH_FOUND,
                 GameDefaults.MATCH_STATUS_READY,
                 GameDefaults.MATCH_STATUS_IN_PROGRESS
             ))) {
            if (rs.next()) {
                return mapMatch(rs);
            }
        }
        return null;
    }

    private MatchRecord fetchMatchById(Connection conn, String matchId) throws Exception {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("""
                 SELECT *
                 FROM matches
                 WHERE id = '%s'
             """.formatted(escapeSql(matchId)))) {
            if (rs.next()) {
                return mapMatch(rs);
            }
        }
        return null;
    }

    private List<MatchRecord> fetchMatchesByStatus(Connection conn, String status) throws Exception {
        List<MatchRecord> matches = new ArrayList<MatchRecord>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("""
                 SELECT *
                 FROM matches
                 WHERE status = '%s'
                 ORDER BY created_at
             """.formatted(escapeSql(status)))) {
            while (rs.next()) {
                matches.add(mapMatch(rs));
            }
        }
        return matches;
    }

    private QueueTicketRecord mapQueueTicket(ResultSet rs) throws Exception {
        QueueTicketRecord ticket = new QueueTicketRecord();
        ticket.ticketId = rs.getString("ticket_id");
        ticket.playerId = rs.getString("player_id");
        ticket.username = rs.getString("username");
        ticket.mode = rs.getString("mode");
        ticket.currentGamemode = rs.getString("current_gamemode");
        ticket.boardWidth = rs.getInt("board_width");
        ticket.boardHeight = rs.getInt("board_height");
        ticket.mmr = rs.getInt("mmr");
        ticket.power = rs.getInt("power");
        ticket.status = rs.getString("status");
        ticket.matchId = rs.getString("match_id");
        ticket.queuedAt = rs.getString("queued_at");
        ticket.lastSeenAt = rs.getString("last_seen_at");
        ticket.createdAt = rs.getString("created_at");
        ticket.updatedAt = rs.getString("updated_at");
        return ticket;
    }

    private MatchRecord mapMatch(ResultSet rs) throws Exception {
        MatchRecord match = new MatchRecord();
        match.id = rs.getString("id");
        match.player1Id = rs.getString("player1_id");
        match.player2Id = rs.getString("player2_id");
        match.player1Username = rs.getString("player1_username");
        match.player2Username = rs.getString("player2_username");
        match.mode = rs.getString("mode");
        match.currentGamemode = rs.getString("current_gamemode");
        match.boardWidth = rs.getInt("board_width");
        match.boardHeight = rs.getInt("board_height");
        match.power = rs.getInt("power");
        match.status = rs.getString("status");
        match.player1Acknowledged = rs.getInt("player1_acknowledged") == 1;
        match.player2Acknowledged = rs.getInt("player2_acknowledged") == 1;
        match.boardLetters = rs.getString("board_letters");
        match.createdAt = rs.getString("created_at");
        match.updatedAt = rs.getString("updated_at");
        return match;
    }

    private boolean hasValidSession(Connection conn, String authPlayerId, String authToken) throws Exception {
        if (isBlank(authPlayerId) || isBlank(authToken)) {
            return false;
        }

        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("""
                 SELECT 1
                 FROM player_sessions
                 WHERE player_id = '%s' AND auth_token = '%s'
             """.formatted(escapeSql(authPlayerId), escapeSql(authToken)))) {
            return rs.next();
        }
    }

    private String validatePlayerId(String id) {
        if (isBlank(id)) {
            return "Player id is required";
        }
        return null;
    }

    private String validateQueueMode(String mode) {
        if (isBlank(mode)) {
            return "mode is required";
        }
        String normalized = mode.trim().toLowerCase(Locale.ROOT);
        if (!GameDefaults.ALLOWED_QUEUE_MODES.contains(normalized)) {
            return "Only casual and competitive matchmaking are supported";
        }
        return null;
    }

    private String validateGamemode(String gamemode) {
        if (isBlank(gamemode)) {
            return "currentGamemode is required";
        }
        if (gamemode.length() > GameDefaults.MAX_GAMEMODE_LENGTH) {
            return "currentGamemode must be " + GameDefaults.MAX_GAMEMODE_LENGTH + " characters or less";
        }
        return null;
    }

    private String validateBoardSize(int width, int height) {
        if (!GameDefaults.isAllowedBoardSize(width, height)) {
            return "Invalid board size. Allowed sizes are 4x4, 4x5, and 5x5";
        }
        return null;
    }

    private String escapeSql(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("'", "''");
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private <T> ResponseEntity<ApiResponse<T>> ok(T data) {
        return ResponseEntity.ok(ApiResponse.success(data));
    }

    private <T> ResponseEntity<ApiResponse<T>> badRequest(String error) {
        return ResponseEntity.badRequest().body(ApiResponse.failure(error));
    }

    private <T> ResponseEntity<ApiResponse<T>> unauthorized(String error) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponse.failure(error));
    }

    private <T> ResponseEntity<ApiResponse<T>> notFound(String error) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.failure(error));
    }

    private <T> ResponseEntity<ApiResponse<T>> conflict(String error) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.failure(error));
    }

    private <T> ResponseEntity<ApiResponse<T>> serviceUnavailable(String error) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(ApiResponse.failure(error));
    }

    private <T> ResponseEntity<ApiResponse<T>> serverError(String context, Exception e) {
        int errorId = ERROR_COUNTER.incrementAndGet();
        Throwable root = e;
        while (root.getCause() != null) {
            root = root.getCause();
        }

        String error = context
            + " [ERR-" + errorId + "]"
            + " | exception=" + e.getClass().getSimpleName()
            + " | message=" + safeMessage(e);

        if (root != e) {
            error += " | root=" + root.getClass().getSimpleName()
                + " | rootMessage=" + safeMessage(root);
        }

        e.printStackTrace();
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiResponse.failure(error));
    }

    private String safeMessage(Throwable t) {
        return t.getMessage() == null ? "<no message>" : t.getMessage();
    }

    private static class PlayerRecord {
        String id;
        String username;
        int banAmount;
        String updatedAt;
    }

    private static class QueueTicketRecord {
        String ticketId;
        String playerId;
        String username;
        String mode;
        String currentGamemode;
        int boardWidth;
        int boardHeight;
        int mmr;
        int power;
        String status;
        String matchId;
        String queuedAt;
        String lastSeenAt;
        String createdAt;
        String updatedAt;
    }

    private static class MatchRecord {
        String id;
        String player1Id;
        String player2Id;
        String player1Username;
        String player2Username;
        String mode;
        String currentGamemode;
        int boardWidth;
        int boardHeight;
        int power;
        String status;
        boolean player1Acknowledged;
        boolean player2Acknowledged;
        String boardLetters;
        String createdAt;
        String updatedAt;

        boolean hasPlayer(String playerId) {
            return player1Id.equals(playerId) || player2Id.equals(playerId);
        }

        boolean isAcknowledged(String playerId) {
            if (player1Id.equals(playerId)) {
                return player1Acknowledged;
            }
            if (player2Id.equals(playerId)) {
                return player2Acknowledged;
            }
            return false;
        }
    }
}
