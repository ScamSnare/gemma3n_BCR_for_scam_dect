#!/bin/zsh

#!/bin/zsh

export RELEASE_KEYSTORE="$(pwd)/keystore/release-key.jks"
export RELEASE_KEY_ALIAS="my-key-alias"

echo -n "Enter keystore password: "
stty -echo
read RELEASE_KEYSTORE_PASSPHRASE
stty echo
echo

echo -n "Enter key password: "
stty -echo
read RELEASE_KEY_PASSPHRASE
stty echo
echo

export RELEASE_KEYSTORE_PASSPHRASE
export RELEASE_KEY_PASSPHRASE

