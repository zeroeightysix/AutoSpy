# AutoSpy
Simple sponge plugin for monitoring players
<br>
Provides the ability to enter 'spy' mode: your player will spectate all online players iteratively in order to view what players are doing and to prevent evildoers from hacking, duping etc

### Commands and permission nodes
`/spyall | /autospy [interval]` where `interval` is the interval between switching to players in seconds (default: `5`), permission node `autospy.spyall`
<br>
permission node: `autospy.exempt`: will exclude a player from the spectating list
