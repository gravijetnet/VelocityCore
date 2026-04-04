[19:13:35 INFO] [discord4j.core.util.EntityUtil]: Unknown channel type 15 with data: ChannelData{id=000000000000000000, type=15, guildId=Possible{000000000000000000}, position=Possible{19}, permissionOverwrites=[OverwriteData{id=000000000000000000, type=0, allow=1049600, deny=0}, OverwriteData{id=000000000000000000, type=0, allow=0, deny=1049600}, OverwriteData{id=000000000000000000, type=0, allow=1049600, deny=0}], name=Possible{🪲・bug-reports}, topic=Possible{Optional.empty}, nsfw=Possible.absent, lastMessageId=Possible{Optional[000000000000000000]}, bitrate=Possible.absent, userLimit=Possible.absent, rateLimitPerUser=Possible{0}, recipients=null, icon=Possible.absent, ownerId=Possible.absent, applicationId=Possible.absent, parentId=Possible{Optional[000000000000000000]}, lastPinTimestamp=Possible.absent, rtcRegion=Possible.absent, videoQualityMode=Possible.absent}
[19:13:35 ERROR] [velocitycore]: Failed to send bug report.
java.lang.ClassCastException: class discord4j.core.object.entity.channel.UnknownChannel cannot be cast to class discord4j.core.object.entity.channel.TextChannel (discord4j.core.object.entity.channel.UnknownChannel and discord4j.core.object.entity.channel.TextChannel are in unnamed module of loader com.velocitypowered.proxy.plugin.PluginClassLoader @253b380a)
at net.gravijet.velocity.core.support.discord.DiscordBot.sendBugReport(DiscordBot.java:450) ~[?:?]
at net.gravijet.velocity.core.support.command.BugCommand.execute(BugCommand.java:65) ~[?:?]
at net.gravijet.velocity.core.support.command.BugCommand.execute(BugCommand.java:16) ~[?:?]
at com.velocitypowered.proxy.command.registrar.InvocableCommandRegistrar.lambda$createLiteral$1(InvocableCommandRegistrar.java:82) ~[velocity.jar:3.5.0-SNAPSHOT (git-d11511c1-b584)]
at com.mojang.brigadier.CommandDispatcher.execute(CommandDispatcher.java:262) ~[velocity.jar:3.5.0-SNAPSHOT (git-d11511c1-b584)]
at com.velocitypowered.proxy.command.VelocityCommandManager.executeImmediately0(VelocityCommandManager.java:237) ~[velocity.jar:3.5.0-SNAPSHOT (git-d11511c1-b584)]
at com.velocitypowered.proxy.command.VelocityCommandManager.lambda$executeImmediatelyAsync$3(VelocityCommandManager.java:298) ~[velocity.jar:3.5.0-SNAPSHOT (git-d11511c1-b584)]
at java.base/java.util.concurrent.CompletableFuture$AsyncSupply.run(CompletableFuture.java:1768) ~[?:?]
at java.base/java.util.concurrent.ThreadPoolExecutor.runWorker(ThreadPoolExecutor.java:1144) ~[?:?]
at java.base/java.util.concurrent.ThreadPoolExecutor$Worker.run(ThreadPoolExecutor.java:642) ~[?:?]
at java.base/java.lang.Thread.run(Thread.java:1583) [?:?]
[19:13:35 INFO] [velocitycore]: Bug report from gravijet: a - a
[19:13:37 INFO] [discord4j.core.util.EntityUtil]: Unknown channel type 15 with data: ChannelData{id=000000000000000000, type=15, guildId=Possible{000000000000000000}, position=Possible{19}, permissionOverwrites=[OverwriteData{id=000000000000000000, type=0, allow=1049600, deny=0}, OverwriteData{id=000000000000000000, type=0, allow=0, deny=1049600}, OverwriteData{id=000000000000000000, type=0, allow=1049600, deny=0}], name=Possible{🪲・bug-reports}, topic=Possible{Optional.empty}, nsfw=Possible.absent, lastMessageId=Possible{Optional[000000000000000000]}, bitrate=Possible.absent, userLimit=Possible.absent, rateLimitPerUser=Possible{0}, recipients=null, icon=Possible.absent, ownerId=Possible.absent, applicationId=Possible.absent, parentId=Possible{Optional[000000000000000000]}, lastPinTimestamp=Possible.absent, rtcRegion=Possible.absent, videoQualityMode=Possible.absent}
[19:13:37 ERROR] [velocitycore]: Failed to send bug report.
java.lang.ClassCastException: class discord4j.core.object.entity.channel.UnknownChannel cannot be cast to class discord4j.core.object.entity.channel.TextChannel (discord4j.core.object.entity.channel.UnknownChannel and discord4j.core.object.entity.channel.TextChannel are in unnamed module of loader com.velocitypowered.proxy.plugin.PluginClassLoader @253b380a)
at net.gravijet.velocity.core.support.discord.DiscordBot.sendBugReport(DiscordBot.java:450) ~[?:?]
at net.gravijet.velocity.core.support.command.BugCommand.execute(BugCommand.java:65) ~[?:?]
at net.gravijet.velocity.core.support.command.BugCommand.execute(BugCommand.java:16) ~[?:?]
at com.velocitypowered.proxy.command.registrar.InvocableCommandRegistrar.lambda$createLiteral$1(InvocableCommandRegistrar.java:82) ~[velocity.jar:3.5.0-SNAPSHOT (git-d11511c1-b584)]
at com.mojang.brigadier.CommandDispatcher.execute(CommandDispatcher.java:262) ~[velocity.jar:3.5.0-SNAPSHOT (git-d11511c1-b584)]
at com.velocitypowered.proxy.command.VelocityCommandManager.executeImmediately0(VelocityCommandManager.java:237) ~[velocity.jar:3.5.0-SNAPSHOT (git-d11511c1-b584)]
at com.velocitypowered.proxy.command.VelocityCommandManager.lambda$executeImmediatelyAsync$3(VelocityCommandManager.java:298) ~[velocity.jar:3.5.0-SNAPSHOT (git-d11511c1-b584)]
at java.base/java.util.concurrent.CompletableFuture$AsyncSupply.run(CompletableFuture.java:1768) ~[?:?]
at java.base/java.util.concurrent.ThreadPoolExecutor.runWorker(ThreadPoolExecutor.java:1144) ~[?:?]
at java.base/java.util.concurrent.ThreadPoolExecutor$Worker.run(ThreadPoolExecutor.java:642) ~[?:?]
at java.base/java.lang.Thread.run(Thread.java:1583) [?:?]
[19:13:37 INFO] [velocitycore]: Bug report from gravijet: a - a a
also the messages and tab completions don't work for the bug report command in minecraft.