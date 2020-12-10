# FabricTPA
[![CurseForge downloads](https://cf.way2muchnoise.eu/short_423295.svg)](https://www.curseforge.com/minecraft/mc-mods/fabrictpa)
[![GitHub package.json version](https://img.shields.io/github/v/release/CodedSakura/FabricTPA)](https://github.com/CodedSakura/FabricTPA)  
A server-side Fabric mod that adds /tpa command-set.  
Works for Minecraft 1.16.2+ (snapshots not fully tested)  
Requires [FabricAPI](https://www.curseforge.com/minecraft/mc-mods/fabric-api)  

## Commands
All commands don't require OP permissions. They're meant for everyone on a server to use.

`/tpa <player>` - Initiates request for you to teleport to `<player>`  
`/tpahere <player>` - Initiates request for `<player>` to teleport to you

`/tpacancel [<player>]` - Cancel a tpa or tpahere request you've initiated, argument required if multiple ongoing  
`/tpaaccept [<player>]` - Accept a tpa or tpahere request you've received, argument required if multiple ongoing  
`/tpadeny [<player>]` - Deny a tpa or tpahere request you've received, argument required if multiple ongoing  

## Configuration
Configuration is done through gamerules. There are currently 3 configurable options.  
Configuration is also saved in `config/FabricTPA.properties`, from which the values are loaded into the gamerules at server startup.
It also updates when a gamerule is changed in-game.

`tpaTimeout` - How long should it take for a tpa or tpahere request to time out, if not accepted/denied/cancelled. Default: 60 (seconds)  
`tpaStandStillTime` - How long should the player stand still for after accepting a tpa or tpahere request. Default: 5 (seconds)  
`tpaDisableBossBar` - Whether to disable the boss bar indication for standing still, if set to true will use action bar for time. Default: false  
