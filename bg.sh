#!/usr/bin/env bash

# Send the query to the terminal
echo -ne "\e]11;?\a"
read -r -d $'\a' -s -t 0.1 RGB

# Extract hex values if the terminal responded (formats vary, e.g., rgb:0000/0000/0000 or rgb:00/00/00)
if [[ "$RGB" =~ rgb:([0-9a-fA-F]+)/([0-9a-fA-F]+)/([0-9a-fA-F]+) ]]; then
    # Take the first two characters of each color channel
    R=$((16#${BASH_REMATCH[1]:0:2}))
    G=$((16#${BASH_REMATCH[2]:0:2}))
    B=$((16#${BASH_REMATCH[3]:0:2}))
    
    # Calculate luminance using the Digital ITU BT.709 formula
    # Y = 0.2126 R + 0.7152 G + 0.0722 B
    LUMINANCE=$(echo "$R $G $B" | awk '{print (0.2126*$1 + 0.7152*$2 + 0.0722*$3)}')
    
    # If luminance is less than 128, it's a dark background
    if (( $(echo "$LUMINANCE < 128" | bc -l) )); then
        echo "dark"
    else
        echo "light"
    fi
else
    # Fallback if terminal doesn't support the query
    echo "unknown (defaulting to dark)"
fi
