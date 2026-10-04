package com.slotq.mcp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.function.Consumer;
import tools.jackson.databind.json.JsonMapper;

/** Optional protected operational directory. Seven dates, finite bytes; no durable delivery promise. */
public final class MetadataFileSink implements Consumer<McpAudit.Event> {
    private final Path directory;
    private final Clock clock;
    private final int retentionDays;
    private final long dailyBytes;
    private final JsonMapper json=JsonMapper.builder().build();
    public MetadataFileSink(Path directory,Clock clock,int retentionDays,long dailyBytes) {
        if(retentionDays<1 || retentionDays>7 || dailyBytes<1024 || dailyBytes>16*1024*1024)
            throw new IllegalArgumentException("Audit retention/size bound");
        this.directory=directory.toAbsolutePath().normalize();this.clock=clock;
        this.retentionDays=retentionDays;this.dailyBytes=dailyBytes;
    }
    @Override public void accept(McpAudit.Event event) {
        try {
            Files.createDirectories(directory);
            LocalDate date=LocalDate.ofInstant(clock.instant(),ZoneOffset.UTC);
            try(var files=Files.newDirectoryStream(directory,"mcp-*.jsonl")) {
                int count=0;
                for(Path file:files) {
                    if(++count>128)throw new IOException("sink cardinality");
                    String name=file.getFileName().toString();
                    if(!name.matches("mcp-\\d{4}-\\d{2}-\\d{2}\\.jsonl"))continue;
                    LocalDate recorded=LocalDate.parse(name.substring(4,14));
                    Path checked=file.toAbsolutePath().normalize();
                    if(!checked.getParent().equals(directory) || Files.isSymbolicLink(checked))throw new IOException("sink path");
                    if(recorded.isBefore(date.minusDays(retentionDays-1)))Files.delete(checked);
                }
            }
            Path target=directory.resolve("mcp-"+date+".jsonl");
            if(Files.isSymbolicLink(target))throw new IOException("sink path");
            byte[] line=(json.writeValueAsString(event)+"\n").getBytes(StandardCharsets.UTF_8);
            if((Files.exists(target)?Files.size(target):0)+line.length>dailyBytes)throw new IOException("sink size");
            Files.write(target,line,StandardOpenOption.CREATE,StandardOpenOption.APPEND);
        }catch(IOException | RuntimeException ignored) {throw new IllegalStateException("Audit sink unavailable",null);}
    }
}
