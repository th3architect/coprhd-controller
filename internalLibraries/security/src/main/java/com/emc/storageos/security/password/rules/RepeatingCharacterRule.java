/*
 * Copyright (c) 2014 EMC Corporation
 * All Rights Reserved
 */

package com.emc.storageos.security.password.rules;

import com.emc.storageos.security.password.Password;
import com.emc.storageos.svcs.errorhandling.resources.BadRequestException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.MessageFormat;

public class RepeatingCharacterRule implements Rule {

    private static final Logger _log = LoggerFactory.getLogger(RepeatingCharacterRule.class);

    private int numCharacters = 3;

    public RepeatingCharacterRule(int number) {
        this.numCharacters = number;
    }

    @Override
    public void validate(Password password) {
        if (numCharacters == 0) {
            return;
        }

        String text = password.getPassword();

        int repeating = 1;
        int max_repeating = 1;
        char old = text.charAt(0);

        for (int i = 1; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == old) {
                repeating++;
                if (repeating > max_repeating) {
                    max_repeating = repeating;
                }
            } else {
                old = c;
                repeating = 1;
            }
        }

        _log.info(MessageFormat.format("expect < {0} repeating character, real = {1}", numCharacters, max_repeating));
        if (max_repeating > numCharacters) {
            _log.info("fail");
            throw BadRequestException.badRequests.passwordInvalidRepeating(numCharacters);
        }

        _log.info("pass");
    }
}
