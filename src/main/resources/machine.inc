; The Machine: memory map, Device registers and their bits. Printed by `6502 defs`; .include it.
; Register details are in the README, "The Machine".

; ---- memory map
RAM         = $0000         ; to $C7FF
STACK       = $0100         ; to $01FF
CHAR_ROM    = $C800         ; to $CFFF, RAM when no --char-rom
SYS_ROM     = $E000         ; to $FFFF, RAM when no --rom
NMI_VEC     = $FFFA
RESET_VEC   = $FFFC
IRQ_VEC     = $FFFE         ; also BRK

; ---- Console
CON         = $D000
CON_DATA    = CON + $00     ; read: next input byte (0 if none); write: send a byte
CON_STATUS  = CON + $01
CON_CTRL    = CON + $02
CON_WAITING = $80           ; CON_STATUS: input waiting
CON_READY   = $40           ; CON_STATUS: ready to send (always)
CON_CLOSED  = $20           ; CON_STATUS: input closed (EOF)
CON_IRQ     = $80           ; CON_CTRL: IRQ while input is waiting

; ---- Timer
TIMER        = $D100
TIMER_LATCH  = TIMER + $00  ; write: latch the count and cycle counter
TIMER_STATUS = TIMER + $01  ; write: clear the expired flag
TIMER_CTRL   = TIMER + $02
TIMER_RELOAD = TIMER + $04  ; 2 bytes, lo/hi, 0 means 65536
TIMER_COUNT  = TIMER + $06  ; 2 bytes, latched count
TIMER_CYCLES = TIMER + $08  ; 4 bytes, latched cycle counter, low byte first
TIMER_EXPIRED = $80         ; TIMER_STATUS
TIMER_RUNNING = $40         ; TIMER_STATUS
TIMER_IRQ     = $80         ; TIMER_CTRL
TIMER_PERIODIC = $02        ; TIMER_CTRL
TIMER_RUN     = $01         ; TIMER_CTRL

; ---- Disk
DISK         = $D200
DISK_CMD     = DISK + $00
DISK_STATUS  = DISK + $01   ; write: clear done
DISK_CTRL    = DISK + $02
DISK_ERROR   = DISK + $03
DISK_SECTOR  = DISK + $04   ; 2 bytes, lo/hi
DISK_PAGE    = DISK + $06   ; RAM page, the high byte of the address
DISK_LAST    = DISK + $08   ; 2 bytes, last Sector number
DISK_READ    = $01          ; DISK_CMD: read Sector into page
DISK_WRITE   = $02          ; DISK_CMD: write page to Sector
DISK_DONE    = $80          ; DISK_STATUS
DISK_BUSY    = $40          ; DISK_STATUS
DISK_FAILED  = $20          ; DISK_STATUS: error
DISK_NOMEDIA = $10          ; DISK_STATUS
DISK_RDONLY  = $08          ; DISK_STATUS
DISK_IRQ     = $80          ; DISK_CTRL: IRQ on done
DISK_E_NOMEDIA = 1          ; DISK_ERROR codes
DISK_E_SECTOR  = 2          ; Sector past the last
DISK_E_PAGE    = 3          ; page is not RAM
DISK_E_RDONLY  = 4
DISK_E_CMD     = 5          ; unknown command
DISK_E_IO      = 6          ; host I/O error

; ---- Exit device
EXIT        = $D300         ; write: stop with the byte as exit code
