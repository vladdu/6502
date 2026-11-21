CON_DATA = $D000
EXIT     = $D300

        *= $0200
start:  ldx #0
@loop:  lda msg,x
        beq done
        sta CON_DATA
        inx
        bne @loop
done:   lda #0
        sta EXIT

msg:    .byte "Hello, 6502!", 10, 0
