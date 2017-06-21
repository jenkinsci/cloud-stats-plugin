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

import hudson.model.Action;
import org.jenkinsci.plugins.cloudstats.ProvisioningActivity.Status;

/**
 * Additional information attached to the {@link PhaseExecution}.
 */
public class PhaseExecutionAttachment implements Action {

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
        return title.replaceAll("\n", " ");
    }

    @Override
    public String getIconFileName() {
        return null;
    }

    @Override
    public @Nonnull String getDisplayName() {
        String title = getTitle();
        return title.length() < 50 ? title: (title.substring(0, 49) + "…");
    }

    /**
     * Url fragment (without slashes) to provide URL subspace for this attachment.
     *
     * @return non-null, in case the attachment serves some more content. null otherwise.
     */
    @Override
    public @CheckForNull String getUrlName() {
        return null;
    }

    public static final class ExceptionAttachment extends PhaseExecutionAttachment {

        // Replaced by text field
        @Deprecated private transient Throwable throwable;

        private /*final*/ @Nonnull String text;

        private Object readResolve() {
            if (text != null) return this;

            // Failed to deserialize
            if (throwable == null) {
                text = "Plugin was unable to deserialize the exception from version 0.12 or older";
            } else {
                text = Functions.printThrowable(throwable);
                throwable = null;
            }
            return this;
        }

        public ExceptionAttachment(@Nonnull ProvisioningActivity.Status status, @Nonnull Throwable throwable) {
            super(status, extractTitle(throwable));
            this.text = Functions.printThrowable(throwable);
        }

        private static String extractTitle(@Nonnull Throwable throwable) {
            String message = throwable.getMessage();
            if (message != null) return message;

            // The message might be empty (NPE for example) so get the type at least
            return throwable.getClass().getName();
        }

        public ExceptionAttachment(@Nonnull ProvisioningActivity.Status status, @Nonnull String title, @Nonnull Throwable throwable) {
            super(status, title);
            this.text = Functions.printThrowable(throwable);
        }

        /**
         * @deprecated Use #getText() instead.
         */
        @Deprecated
        public Throwable getCause() {
            return throwable;
        }

        public @Nonnull String getText() {
            return text;
        }

        public String toString() {
            return "Exception attachment: " + text;
        }

        @Override
        public @CheckForNull String getUrlName() {
            return "exception";
        }
    }
}
