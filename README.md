# FabricTPA
A server-side Fabric mod that adds /tpa command-set.  
Works for Minecraft 1.16.2 - 1.16.4  
Requires [FabricAPI](https://www.curseforge.com/minecraft/mc-mods/fabric-api)  

## Commands
All commands don't require OP permissions. They're meant for everyone on a server to use.

`/tpa <player>` - Initiates request for you to teleport to `<player>`  
`/tpahere <player>` - Initiates request for `<player>` to teleport to you

`/tpacancel [<player>]` - Cancel a tpa or tpahere request you've initiated, argument required if multiple ongoing  
`/tpaaccept [<player>]` - Accept a tpa or tpahere request you've received, argument required if multiple ongoing  
`/tpadeny [<player>]` - Deny a tpa or tpahere request you've received, argument required if multiple ongoing  

## Configuration
Configuration is done through gamerules. There are currently 2 configurable options.

`tpaTimeout` - How long should it take for a tpa or tpahere request to time out, if not accepted/denied/cancelled. Default: 60 (seconds)  
`tpaStandStillTime` - How long should the player stand still for after accepting a tpa or tpahere request. Default: 5 (seconds)
