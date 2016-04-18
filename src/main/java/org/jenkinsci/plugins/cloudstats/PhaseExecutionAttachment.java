/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2016 Red Hat, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package org.jenkinsci.plugins.cloudstats;

import hudson.Functions;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;

import org.jenkinsci.plugins.cloudstats.ProvisioningActivity.Status;

/**
 * Additional information attached to the {@link org.jenkinsci.plugins.cloudstats.ProvisioningActivity.PhaseExecution}.
 */
public class PhaseExecutionAttachment {

    private final @Nonnull ProvisioningActivity.Status status;
    private final @Nonnull String title;

    public PhaseExecutionAttachment(@Nonnull ProvisioningActivity.Status status, @Nonnull String title) {
        this.status = status;
        this.title = title;
    }

    /**
     * Status the execution entered once this got attached.
     *
     * @return {@link Status#OK} in case of informative attachment, {@link Status#WARN} in case provisioning continued, but there is
     * something worth attention on this attachment anyway or {@link Status#FAIL} in case provisioning failed with this attachment
     * explaining the cause.
     */
    public @Nonnull ProvisioningActivity.Status getStatus() {
        return status;
    }

    /**
     * Single line description of the attachment nature.
     */
    public @Nonnull String getTitle() {
        return title;
    }

    /**
     * Url fragment (without slashes) to provide URL subspace for this attachment.
     *
     * @return non-null, in case the attachment serves some more content. null otherwise.
     */
    public @CheckForNull String getUrl() {
        return null;
    }

    public static final class ExceptionAttachment extends PhaseExecutionAttachment {

        private final @Nonnull Throwable throwable;

        public ExceptionAttachment(@Nonnull ProvisioningActivity.Status status, @Nonnull String title, @Nonnull Throwable throwable) {
            super(status, title);
            this.throwable = throwable;
        }

        public Throwable getCause() {
            return throwable;
        }

        public String toString() {
            return Functions.printThrowable(throwable);
        }
    }
}
